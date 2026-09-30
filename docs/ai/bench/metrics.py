#!/usr/bin/env python3
"""Salus agent benchmark metrics (stdlib only, Python 3.9+).

  metrics.py meta <run-dir>                         # called by bench.sh after each run
  metrics.py collect <results-dir> [-o metrics.csv]
  metrics.py fields <results-dir>                   # show which raw-log fields exist (parser validation)
  metrics.py summarize <metrics.csv> <scores.csv> [-o summary.md]

Values a log does not provide are written as "N/A"; nothing is estimated.
Parsers follow Claude Code `--output-format stream-json` and `codex exec --json` event shapes;
run `fields` on real logs before trusting the numbers (AGENT_EVALUATION.md, section 6).
"""
import csv
import json
import os
import re
import shlex
import statistics
import sys
from pathlib import Path

NA = "N/A"
MAIN_TASKS = ("A", "B", "C", "D")
TOOL_BUDGET = {"A": 30, "B": 25, "C": 20, "D": 12, "S1": 15}
HARNESS_PAIRS = (("claude-before", "claude-after"), ("codex-base", "codex-optimized"))
PRACTICAL_PAIR = ("claude-after", "codex-optimized")
IN_SCOPE_PREFIXES = ("backend/src/", "backend/pom.xml", "AGENTS.md", "CLAUDE.md",
                     "docs/ai/", ".claude/skills/", ".agents/skills/")
EXCLUDED_MARKERS = ("node_modules/", "/target/", "target/", "Pods/", "/results/", "poc/", "dist/")
LARGE_FILE_LINES = 300
READ_FULL = {"cat", "bat", "less", "more"}
LISTING = {"find", "ls", "tree", "fd"}
SEARCH = {"rg", "grep", "egrep", "ag", "ack"}
BUILD = {"mvn", "mvnw", "gradle", "gradlew", "npm", "npx", "yarn", "pnpm", "java", "docker",
         "python", "python3", "node", "eval.sh"}
WRITE = {"rm", "mv", "cp", "touch", "mkdir", "tee", "chmod", "truncate", "patch", "apply_patch"}
GIT_READ = {"status", "diff", "log", "show", "blame", "grep", "ls-files", "rev-parse", "branch"}


# ---------------------------------------------------------------- shell parsing
def unwrap_shell(command):
    try:
        tokens = shlex.split(command)
    except ValueError:
        return command
    if len(tokens) >= 3 and os.path.basename(tokens[0]) in {"bash", "zsh", "sh"} and tokens[1] in {"-lc", "-c"}:
        return tokens[2]
    return command


def split_commands(script):
    """Split into commands (&&, ||, ;), each a list of pipeline stages (token lists)."""
    lexer = shlex.shlex(script, posix=True, punctuation_chars=";&|")
    lexer.whitespace_split = True
    lexer.commenters = ""
    try:
        tokens = list(lexer)
    except ValueError:
        return [[script.split()]]
    commands, stages, current = [], [], []
    for tok in tokens:
        if tok in {"&&", "||", ";", "&"}:
            if current:
                stages.append(current)
            if stages:
                commands.append(stages)
            stages, current = [], []
        elif tok == "|":
            if current:
                stages.append(current)
            current = []
        else:
            current.append(tok)
    if current:
        stages.append(current)
    if stages:
        commands.append(stages)
    return commands


def looks_like_path(arg):
    if not arg or arg.startswith("-") or re.fullmatch(r"[\d,;$p]+", arg):
        return False
    return "/" in arg or re.search(r"\.[A-Za-z0-9]{1,6}$", arg) is not None


def classify_shell(command):
    """Return list of (kind, path_or_None). kinds: read_full, read_targeted, search, listing,
    git_read, git_write, build, write, cd, other."""
    events = []
    for stages in split_commands(unwrap_shell(command)):
        later_limits = any(s and os.path.basename(s[0]) in {"sed", "head", "tail", "awk"} for s in stages[1:])
        for index, stage in enumerate(stages):
            args = [a for a in stage if not re.match(r"^[A-Za-z_][A-Za-z0-9_]*=", a)]
            if not args:
                continue
            prog = os.path.basename(args[0])
            rest = args[1:]
            if any(a.startswith(">") for a in rest):
                events.append(("write", None))
            if prog == "cd":
                events.append(("cd", rest[0] if rest else None))
            elif prog in READ_FULL or prog == "nl":
                kind = "read_targeted" if (index == 0 and later_limits) else "read_full"
                paths = [a for a in rest if looks_like_path(a)]
                if paths:
                    events.extend((kind, p) for p in paths)
                else:
                    events.append(("other", None))
            elif prog in {"sed", "head", "tail", "awk"}:
                if prog == "sed" and any(a.startswith("-i") for a in rest):
                    events.append(("write", None))
                    continue
                if prog == "sed" and "-n" not in rest:
                    events.append(("other", None))
                    continue
                paths = [a for a in rest if looks_like_path(a)]
                if paths:
                    events.extend(("read_targeted", p) for p in paths)
                else:
                    events.append(("other", None))
            elif prog in SEARCH:
                events.append(("listing" if "--files" in rest else "search", None))
            elif prog == "git":
                sub = next((a for a in rest if not a.startswith("-")), "")
                if sub == "grep":
                    events.append(("search", None))
                elif sub == "ls-files":
                    events.append(("listing", None))
                elif sub in GIT_READ:
                    events.append(("git_read", None))
                else:
                    events.append(("git_write", None))
            elif prog in LISTING:
                events.append(("listing", None))
            elif prog in BUILD or prog.endswith("eval.sh"):
                events.append(("build", None))
            elif prog in WRITE:
                events.append(("write", None))
            else:
                events.append(("other", None))
    return events


# ---------------------------------------------------------------- log parsing
def load_events(path):
    events = []
    try:
        handle = open(path, encoding="utf-8", errors="replace")
    except OSError:
        return events
    with handle:
        for line in handle:
            line = line.strip()
            if not line.startswith("{"):
                continue
            try:
                events.append(json.loads(line))
            except json.JSONDecodeError:
                continue
    return events


def _text_length(content):
    if isinstance(content, str):
        return len(content)
    if isinstance(content, list):
        return sum(_text_length(block.get("text", "") if isinstance(block, dict) else block) for block in content)
    return 0


def parse_claude(events):
    calls, by_id = [], {}
    usage, result, model = None, None, NA
    for ev in events:
        etype = ev.get("type")
        if etype == "system" and ev.get("subtype") == "init":
            model = ev.get("model", NA)
        elif etype == "assistant":
            for block in (ev.get("message") or {}).get("content") or []:
                if block.get("type") != "tool_use":
                    continue
                call_id = block.get("id")
                if call_id is not None and call_id in by_id:
                    continue
                call = {"id": call_id, "name": block.get("name", ""), "input": block.get("input") or {},
                        "sub": ev.get("parent_tool_use_id") is not None, "output_chars": NA}
                calls.append(call)
                if call_id is not None:
                    by_id[call_id] = call
        elif etype == "user":
            content = (ev.get("message") or {}).get("content")
            for block in content if isinstance(content, list) else []:
                if isinstance(block, dict) and block.get("type") == "tool_result" and block.get("tool_use_id") in by_id:
                    by_id[block["tool_use_id"]]["output_chars"] = _text_length(block.get("content"))
        elif etype == "result":
            result = ev
            usage = ev.get("usage")
    tokens = {k: NA for k in ("input", "cache_write", "cache_read", "output", "reasoning", "total_input")}
    if usage:
        tokens["input"] = usage.get("input_tokens", NA)
        tokens["cache_write"] = usage.get("cache_creation_input_tokens", NA)
        tokens["cache_read"] = usage.get("cache_read_input_tokens", NA)
        tokens["output"] = usage.get("output_tokens", NA)
        parts = [tokens["input"], tokens["cache_write"], tokens["cache_read"]]
        tokens["total_input"] = sum(parts) if all(isinstance(p, int) for p in parts) else NA
    extra = {
        "model": model,
        "cost_usd": result.get("total_cost_usd", NA) if result else NA,
        "agent_duration_ms": result.get("duration_ms", NA) if result else NA,
        "num_turns": result.get("num_turns", NA) if result else NA,
        "final_text": result.get("result", "") if result else "",
        "result_seen": result is not None,
    }
    actions = []
    for call in calls:
        name, data = call["name"], call["input"]
        if name == "Read":
            kind = "read_targeted" if ("offset" in data or "limit" in data) else "read_full"
            actions.append((kind, data.get("file_path"), call))
        elif name == "Grep":
            actions.append(("search", None, call))
        elif name == "Glob":
            actions.append(("listing", None, call))
        elif name == "Bash":
            for kind, target in classify_shell(data.get("command", "")):
                actions.append((kind, target, call))
        elif name in {"Edit", "Write", "MultiEdit", "NotebookEdit"}:
            actions.append(("write", data.get("file_path"), call))
        elif name in {"WebFetch", "WebSearch"}:
            actions.append(("web", None, call))
        elif name == "Skill":
            actions.append(("skill", None, call))
        elif name in {"Agent", "Task"}:
            actions.append(("subagent", None, call))
        else:
            actions.append(("other", None, call))
    keys = [(c["name"], json.dumps(c["input"], sort_keys=True, ensure_ascii=False)) for c in calls]
    return calls, actions, tokens, extra, keys


def parse_codex(events):
    calls, seen, actions, keys = [], set(), [], []
    totals = {"input_tokens": 0, "cached_input_tokens": 0, "cache_write_input_tokens": 0,
              "output_tokens": 0, "reasoning_output_tokens": 0}
    present = {k: False for k in totals}
    final_text, turn_completed = "", False
    for ev in events:
        etype = ev.get("type")
        if etype == "turn.completed":
            turn_completed = True
            for key, value in (ev.get("usage") or {}).items():
                if key in totals and isinstance(value, int):
                    totals[key] += value
                    present[key] = True
        elif etype == "item.completed":
            item = ev.get("item") or {}
            details = item.get("details") if isinstance(item.get("details"), dict) else item
            itype = details.get("type") or item.get("type")
            item_id = item.get("id")
            if item_id is not None:
                if item_id in seen:
                    continue
                seen.add(item_id)
            if itype == "agent_message":
                final_text = details.get("text", final_text)
                continue
            if itype in {"reasoning", "todo_list", "error", None}:
                continue
            output = details.get("aggregated_output")
            call = {"id": item_id, "name": itype, "input": details, "sub": itype == "collab_tool_call",
                    "output_chars": len(output) if isinstance(output, str) else NA}
            calls.append(call)
            if itype == "command_execution":
                command = details.get("command", "")
                command = " ".join(command) if isinstance(command, list) else command
                keys.append((itype, command))
                for kind, target in classify_shell(command):
                    actions.append((kind, target, call))
            else:
                keys.append((itype, json.dumps(details, sort_keys=True, ensure_ascii=False)[:500]))
                kind = {"file_change": "write", "web_search": "web", "mcp_tool_call": "other",
                        "collab_tool_call": "subagent"}.get(itype, "other")
                actions.append((kind, None, call))
    tokens = {
        "input": totals["input_tokens"] if present["input_tokens"] else NA,
        "cache_read": totals["cached_input_tokens"] if present["cached_input_tokens"] else NA,
        "cache_write": totals["cache_write_input_tokens"] if present["cache_write_input_tokens"] else NA,
        "output": totals["output_tokens"] if present["output_tokens"] else NA,
        "reasoning": totals["reasoning_output_tokens"] if present["reasoning_output_tokens"] else NA,
    }
    # OpenAI usage counts cached tokens inside input_tokens; flag logs that contradict that.
    tokens["total_input"] = tokens["input"]
    if isinstance(tokens["input"], int) and isinstance(tokens["cache_read"], int) and tokens["cache_read"] > tokens["input"]:
        tokens["total_input"] = "CHECK"
    extra = {"model": NA, "cost_usd": NA, "agent_duration_ms": NA, "num_turns": NA, "final_text": final_text,
             "result_seen": turn_completed}
    return calls, actions, tokens, extra, keys


# ---------------------------------------------------------------- metrics
def normalize_path(path, worktree):
    if not path:
        return None, False
    path = path.strip().strip("'\"")
    if worktree and os.path.isabs(path):
        try:
            rel = os.path.relpath(path, worktree)
        except ValueError:
            return path, True
        return (path, True) if rel.startswith("..") else (rel, False)
    if path.startswith(".."):
        return path, True
    return path[2:] if path.startswith("./") else path, False


def count_lines(worktree, rel):
    try:
        with open(os.path.join(worktree, rel), "rb") as handle:
            return sum(1 for _ in handle)
    except OSError:
        return None


def _sum_known(values):
    known = [v for v in values if isinstance(v, int)]
    return sum(known) if known and len(known) == len(values) else NA


def run_metrics(run_dir):
    meta = json.loads((run_dir / "meta.json").read_text(encoding="utf-8"))
    events = load_events(run_dir / "events.jsonl")
    parser = parse_claude if meta.get("agent") == "claude" else parse_codex
    calls, actions, tokens, extra, keys = parser(events)
    worktree = meta.get("worktree", "")
    reads, outside, excluded, out_of_scope, large_full = [], 0, 0, 0, 0
    read_calls = {}
    counts = {k: 0 for k in ("search", "listing", "git_read", "git_write", "build", "write", "web", "skill", "subagent")}
    for kind, target, call in actions:
        if kind in counts:
            counts[kind] += 1
        if kind not in {"read_full", "read_targeted"}:
            continue
        rel, is_outside = normalize_path(target, worktree)
        if rel is None:
            continue
        if is_outside:
            outside += 1
            continue
        reads.append((kind, rel))
        read_calls[id(call)] = call
        if any(marker in rel for marker in EXCLUDED_MARKERS):
            excluded += 1
        if not rel.startswith(IN_SCOPE_PREFIXES):
            out_of_scope += 1
        if kind == "read_full":
            lines = count_lines(worktree, rel)
            if lines is not None and lines > LARGE_FILE_LINES:
                large_full += 1
    total_reads = len(reads)
    unique = len({rel for _, rel in reads})
    full = sum(1 for kind, _ in reads if kind == "read_full")
    repeated_cmds = sum(n - 1 for n in _counter(keys).values() if n > 1)
    task = meta.get("task", "")
    label = meta.get("label") or ""
    row = {
        "date": run_dir.parent.parent.name, "variant": meta.get("variant"), "label": label,
        "condition": meta.get("variant") + ("+" + label if label else ""), "agent": meta.get("agent"),
        "task": task, "run": meta.get("run"), "model": extra["model"], "agent_version": meta.get("agent_version"),
        "commit": (meta.get("commit") or "")[:12], "exit_code": meta.get("exit_code"),
        "result_event_seen": extra["result_seen"],
        "wall_seconds": meta.get("wall_seconds"), "agent_duration_ms": extra["agent_duration_ms"],
        "num_turns": extra["num_turns"], "cost_usd": extra["cost_usd"],
        "input_tokens": tokens["input"], "cache_read_tokens": tokens["cache_read"],
        "cache_write_tokens": tokens["cache_write"], "output_tokens": tokens["output"],
        "reasoning_tokens": tokens.get("reasoning", NA), "total_input_tokens": tokens["total_input"],
        "tool_calls": len(calls), "subagent_tool_calls": sum(1 for c in calls if c["sub"]),
        "file_reads": total_reads, "full_reads": full, "targeted_reads": total_reads - full,
        "unique_files_read": unique, "repeated_reads": total_reads - unique,
        "re_read_ratio": _ratio(total_reads - unique, total_reads), "full_read_ratio": _ratio(full, total_reads),
        "read_output_chars": _sum_known([c["output_chars"] for c in read_calls.values()]),
        "tool_output_chars": _sum_known([c["output_chars"] for c in calls]),
        "large_full_reads": large_full, "out_of_scope_reads": out_of_scope, "excluded_path_reads": excluded,
        "outside_repo_reads": outside, "searches": counts["search"], "listings": counts["listing"],
        "git_read_cmds": counts["git_read"], "git_write_cmds": counts["git_write"], "build_cmds": counts["build"],
        "write_attempts": counts["write"], "web_calls": counts["web"], "skill_calls": counts["skill"],
        "subagent_calls": counts["subagent"], "repeated_identical_calls": repeated_cmds,
        "worktree_changed": meta.get("worktree_changed"),
    }
    row["auto_violation"] = bool(row["worktree_changed"] or outside or counts["write"] or counts["build"]
                                 or counts["git_write"] or counts["web"])
    row["auto_context_eff_15"] = _context_score(row)
    row["auto_tool_eff_10"] = _tool_score(row, TOOL_BUDGET.get(task))
    return row


def _counter(items):
    counts = {}
    for item in items:
        counts[item] = counts.get(item, 0) + 1
    return counts


def _ratio(numerator, denominator):
    return round(numerator / denominator, 3) if denominator else NA


def _context_score(row):
    score = 5 if row["large_full_reads"] == 0 else 3 if row["large_full_reads"] <= 2 else 0
    ratio = row["re_read_ratio"]
    score += 5 if ratio == NA or ratio <= 0.10 else 3 if ratio <= 0.25 else 0
    score += 5 if row["out_of_scope_reads"] <= 2 else 3 if row["out_of_scope_reads"] <= 5 else 0
    return score


def _tool_score(row, budget):
    if not budget:
        return NA
    calls = row["tool_calls"]
    score = 10 if calls <= budget else 6 if calls <= budget * 1.5 else 3 if calls <= budget * 2 else 0
    if row["repeated_identical_calls"] >= 2:
        score -= 2
    return max(score, 0)


# ---------------------------------------------------------------- commands
def cmd_meta(run_dir):
    run_dir = Path(run_dir)
    env = os.environ
    home = Path.home()
    before = (run_dir / "status-before.txt").read_text(encoding="utf-8") if (run_dir / "status-before.txt").exists() else ""
    after = (run_dir / "status-after.txt").read_text(encoding="utf-8") if (run_dir / "status-after.txt").exists() else ""
    start, end = int(env.get("BENCH_META_START", "0")), int(env.get("BENCH_META_END", "0"))
    meta = {
        "agent": env.get("BENCH_META_AGENT"), "variant": env.get("BENCH_META_VARIANT"),
        "label": env.get("BENCH_META_LABEL", ""),
        "task": env.get("BENCH_META_TASK"), "run": env.get("BENCH_META_RUN"),
        "commit": env.get("BENCH_META_COMMIT"), "worktree": env.get("BENCH_META_WORKTREE"),
        "agent_version": env.get("BENCH_META_VERSION"), "extra_args": env.get("BENCH_META_ARGS", ""),
        "start_epoch": start, "end_epoch": end, "wall_seconds": end - start,
        "exit_code": int(env.get("BENCH_META_RC", "-1")), "timeout_seconds": int(env.get("BENCH_META_TIMEOUT", "0")),
        "worktree_changed": before != after,
        "user_level_instructions_present": {
            "~/.claude/CLAUDE.md": (home / ".claude" / "CLAUDE.md").exists(),
            "~/.claude/skills": (home / ".claude" / "skills").is_dir(),
            "~/.codex/AGENTS.md": (home / ".codex" / "AGENTS.md").exists(),
            "~/.codex/AGENTS.override.md": (home / ".codex" / "AGENTS.override.md").exists(),
            "~/.agents/skills": (home / ".agents" / "skills").is_dir(),
        },
    }
    (run_dir / "meta.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
    final = run_dir / "final.md"
    if meta["agent"] == "claude" and not final.exists():
        _, _, _, extra, _ = parse_claude(load_events(run_dir / "events.jsonl"))
        final.write_text(extra["final_text"] or "", encoding="utf-8")


def _run_dirs(results_dir):
    return [p.parent for p in sorted(Path(results_dir).rglob("events.jsonl")) if (p.parent / "meta.json").exists()]


def cmd_collect(results_dir, output):
    rows = [run_metrics(d) for d in _run_dirs(results_dir)]
    if not rows:
        sys.exit("no runs found under " + str(results_dir))
    with open(output, "w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)
    print("wrote {} rows to {}".format(len(rows), output))


def _keys_of(value, prefix="", depth=0):
    if not isinstance(value, dict) or depth > 2:
        return []
    names = []
    for key, sub in value.items():
        name = prefix + key
        names.append(name)
        if key in {"usage", "item", "message", "details"}:
            names.extend(_keys_of(sub, name + ".", depth + 1))
    return names


def cmd_fields(results_dir):
    for run_dir in _run_dirs(results_dir):
        meta = json.loads((run_dir / "meta.json").read_text(encoding="utf-8"))
        events = load_events(run_dir / "events.jsonl")
        types, keys, tools, samples = {}, {}, {}, []
        for ev in events:
            etype = ev.get("type", "?") + ("/" + ev["subtype"] if isinstance(ev.get("subtype"), str) else "")
            types[etype] = types.get(etype, 0) + 1
            if etype.startswith(("result", "turn.completed", "system/init", "item.completed", "thread.started")):
                keys.setdefault(etype, set()).update(_keys_of(ev))
            if ev.get("type") == "assistant":
                for block in (ev.get("message") or {}).get("content") or []:
                    if block.get("type") == "tool_use":
                        tools[block.get("name")] = tools.get(block.get("name"), 0) + 1
                        if len(samples) < 4:
                            samples.append(json.dumps(block.get("input"), ensure_ascii=False)[:160])
            if ev.get("type") == "item.completed":
                item = ev.get("item") or {}
                itype = item.get("type") or (item.get("details") or {}).get("type")
                tools[itype] = tools.get(itype, 0) + 1
                if itype == "command_execution" and len(samples) < 4:
                    samples.append(str(item.get("command") or (item.get("details") or {}).get("command"))[:160])
        print("== {} ({} {} {})".format(run_dir, meta.get("agent"), meta.get("variant"), meta.get("task")))
        print("  exit_code:", meta.get("exit_code"), " events:", len(events))
        print("  event types:", json.dumps(types, ensure_ascii=False))
        for etype, names in sorted(keys.items()):
            print("  keys[{}]: {}".format(etype, ", ".join(sorted(names))))
        print("  tools/items:", json.dumps(tools, ensure_ascii=False))
        for sample in samples:
            print("  sample:", sample)
        stderr = run_dir / "stderr.log"
        if stderr.exists() and stderr.stat().st_size:
            print("  stderr (first 300 chars):", stderr.read_text(encoding="utf-8", errors="replace")[:300].replace("\n", " | "))


def _num(value):
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _aggregate(items):
    """items: list of (total, success, row). Returns dict of display values."""
    wins = [row for _, ok, row in items if ok]
    scores = [total for total, _, _ in items]

    def per_success(field, digits=1):
        values = [_num(r[field]) for r in wins]
        if not wins or any(v is None for v in values):
            return NA
        return round(sum(values) / len(wins), digits)

    token_pairs = [(_num(r["total_input_tokens"]), _num(r["output_tokens"])) for r in wins]
    tokens = NA if not wins or any(a is None or b is None for a, b in token_pairs) else round(sum(a + b for a, b in token_pairs) / len(wins))
    reads = sum(int(r["file_reads"]) for _, _, r in items)
    walls = [_num(r["wall_seconds"]) for _, _, r in items if _num(r["wall_seconds"]) is not None]
    return {
        "runs": len(items), "success": len(wins),
        "mean score": round(statistics.mean(scores), 1) if scores else NA,
        "tokens/success": tokens, "tool calls/success": per_success("tool_calls"),
        "files read/success": per_success("file_reads"), "read chars/success": per_success("read_output_chars", 0),
        "full-read ratio": _ratio(sum(int(r["full_reads"]) for _, _, r in items), reads),
        "re-read ratio": _ratio(sum(int(r["repeated_reads"]) for _, _, r in items), reads),
        "mean wall s": round(statistics.mean(walls), 1) if walls else NA,
        "cost/success": per_success("cost_usd", 4),
    }


def _table(title, groups, conditions, note):
    columns = ["runs", "success", "mean score", "tokens/success", "tool calls/success", "files read/success",
               "read chars/success", "full-read ratio", "re-read ratio", "mean wall s", "cost/success"]
    lines = ["## " + title, "", note, "", "| condition | " + " | ".join(columns) + " |",
             "|---" * (len(columns) + 1) + "|"]
    for condition in conditions:
        if condition not in groups:
            lines.append("| {} | {} |".format(condition, " | ".join(["no data"] + [""] * (len(columns) - 1))))
            continue
        agg = _aggregate(groups[condition])
        lines.append("| {} | {} |".format(condition, " | ".join(str(agg[c]) for c in columns)))
    return lines + [""]


def cmd_summarize(metrics_csv, scores_csv, output):
    metrics = {(r["date"], r.get("condition") or r["variant"], r["task"], r["run"]): r
               for r in csv.DictReader(open(metrics_csv, encoding="utf-8"))}
    groups, per_task_counts = {}, {}
    for score in csv.DictReader(open(scores_csv, encoding="utf-8")):
        condition = score.get("condition") or score["variant"]
        key = (score["date"], condition, score["task"], score["run"])
        row = metrics.get(key)
        if row is None:
            print("warning: no metrics for", key, file=sys.stderr)
            continue
        if score["task"] not in MAIN_TASKS:
            continue
        context = _num(score.get("context_eff_15")) if score.get("context_eff_15") else _num(row["auto_context_eff_15"])
        tool = _num(score.get("tool_eff_10")) if score.get("tool_eff_10") else _num(row["auto_tool_eff_10"])
        parts = [_num(score.get("correctness_30")), _num(score.get("evidence_20")), _num(score.get("completion_20")),
                 context, tool, _num(score.get("clarity_5"))]
        if any(p is None for p in parts):
            print("warning: incomplete scores for", key, file=sys.stderr)
            continue
        total = sum(parts)
        violation = score.get("constraint_violation", "").strip().upper() == "Y" or row["auto_violation"] == "True"
        success = total >= 70 and parts[0] >= 21 and not violation
        groups.setdefault(condition, []).append((total, success, row))
        per_task_counts[(condition, score["task"])] = per_task_counts.get((condition, score["task"]), 0) + 1
    small = min(per_task_counts.values()) if per_task_counts else 0
    lines = ["# Benchmark summary (MAIN tasks A-D)", ""]
    if small < 3:
        lines += ["> Fewer than 3 runs per condition and task: descriptive only. Do not draw efficiency or quality conclusions.", ""]
    for before, after in HARNESS_PAIRS:
        lines += _table("Harness effect: {} vs {}".format(before, after), groups, (before, after),
                        "Same agent, same model settings, instructions changed. Differences may be attributed to the harness only within this pair.")
    lines += _table("Practical agent comparison: {} vs {}".format(*PRACTICAL_PAIR), groups, PRACTICAL_PAIR,
                    "Different agents and models with the same knowledge base. Describes practical behavior; it does not show that the harness makes one agent better.")
    others = sorted(c for c in groups if c not in {x for pair in HARNESS_PAIRS for x in pair})
    if others:
        lines += _table("Other conditions (e.g. effort experiment labels)", groups, others,
                        "Compare only rows that differ in exactly one setting.")
    Path(output).write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))


def main(argv):
    if len(argv) >= 3 and argv[1] == "meta":
        cmd_meta(argv[2])
    elif len(argv) >= 3 and argv[1] == "collect":
        cmd_collect(argv[2], argv[4] if len(argv) >= 5 and argv[3] == "-o" else "metrics.csv")
    elif len(argv) >= 3 and argv[1] == "fields":
        cmd_fields(argv[2])
    elif len(argv) >= 4 and argv[1] == "summarize":
        cmd_summarize(argv[2], argv[3], argv[5] if len(argv) >= 6 and argv[4] == "-o" else "summary.md")
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv)
