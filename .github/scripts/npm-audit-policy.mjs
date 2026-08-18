#!/usr/bin/env node

import { spawnSync } from 'node:child_process';

const severityRank = {
  info: 0,
  low: 1,
  moderate: 2,
  high: 3,
  critical: 4,
};

const policies = {
  frontend: new Map([
    [
      'https://github.com/advisories/GHSA-w3rx-r6r6-pgpr',
      {
        dependency: 'image-size',
        reason: 'Expo Metro의 빌드 전용 전이 의존성이며 현재 공개된 패치 버전이 없습니다.',
      },
    ],
    [
      'https://github.com/advisories/GHSA-5p2g-fcmc-qvqq',
      {
        dependency: 'image-size',
        reason: 'Expo Metro의 빌드 전용 전이 의존성이며 현재 공개된 패치 버전이 없습니다.',
      },
    ],
  ]),
};

const [threshold = 'high', policyName] = process.argv.slice(2);
const thresholdRank = severityRank[threshold];
const allowedAdvisories = policies[policyName];

if (thresholdRank === undefined || !allowedAdvisories) {
  console.error(`사용법: node npm-audit-policy.mjs <${Object.keys(severityRank).join('|')}> <${Object.keys(policies).join('|')}>`);
  process.exit(2);
}

const audit = spawnSync('npm', ['audit', '--json'], {
  cwd: process.cwd(),
  encoding: 'utf8',
  shell: false,
});

if (audit.error) {
  console.error(`npm audit 실행 실패: ${audit.error.message}`);
  process.exit(2);
}

let report;
try {
  report = JSON.parse(audit.stdout);
} catch (error) {
  console.error('npm audit 결과를 JSON으로 해석하지 못했습니다.');
  if (audit.stderr) console.error(audit.stderr.trim());
  process.exit(2);
}

if (report.error || !report.vulnerabilities || !report.metadata?.vulnerabilities) {
  console.error('npm audit가 유효한 취약점 보고서를 반환하지 않았습니다.');
  if (report.error) console.error(JSON.stringify(report.error));
  process.exit(2);
}

const vulnerabilities = report.vulnerabilities;

const collectRootAdvisories = (name, visited = new Set()) => {
  if (visited.has(name)) return [];
  const vulnerability = vulnerabilities[name];
  if (!vulnerability) return [{ unknownDependency: name }];

  const nextVisited = new Set(visited);
  nextVisited.add(name);

  return vulnerability.via.flatMap(via => {
    if (typeof via === 'string') return collectRootAdvisories(via, nextVisited);
    return [{ ...via, rootDependency: name }];
  });
};

const blocking = [];
const allowed = new Map();

for (const [name, vulnerability] of Object.entries(vulnerabilities)) {
  if ((severityRank[vulnerability.severity] ?? -1) < thresholdRank) continue;

  const roots = collectRootAdvisories(name).filter(root => {
    if (root.unknownDependency) return true;
    return (severityRank[root.severity] ?? -1) >= thresholdRank;
  });

  if (roots.length === 0) {
    blocking.push({ name, title: '원인을 확인할 수 없는 취약점 경로' });
    continue;
  }

  for (const root of roots) {
    if (root.unknownDependency) {
      blocking.push({ name, title: `알 수 없는 의존성 경로: ${root.unknownDependency}` });
      continue;
    }

    const policy = allowedAdvisories.get(root.url);
    const rootPackage = vulnerabilities[root.rootDependency];
    const isAllowedTransitiveDependency = policy
      && policy.dependency === root.dependency
      && rootPackage
      && rootPackage.isDirect === false;

    if (!isAllowedTransitiveDependency) {
      blocking.push({ name, title: root.title, url: root.url });
      continue;
    }

    allowed.set(root.url, {
      ...root,
      reason: policy.reason,
    });
  }
}

if (allowed.size > 0) {
  console.warn('패치 미제공 전이 의존성 예외:');
  for (const advisory of allowed.values()) {
    console.warn(`- ${advisory.title}`);
    console.warn(`  ${advisory.url}`);
    console.warn(`  ${advisory.reason}`);
  }
}

if (blocking.length > 0) {
  console.error(`\n${threshold} 이상 차단 대상 취약점:`);
  const unique = new Map(blocking.map(item => [`${item.name}:${item.url ?? item.title}`, item]));
  for (const item of unique.values()) {
    console.error(`- ${item.name}: ${item.title}${item.url ? ` (${item.url})` : ''}`);
  }
  process.exit(1);
}

const totals = report.metadata.vulnerabilities;
console.log(`\n보안 정책 통과: critical=${totals.critical}, high=${totals.high}, moderate=${totals.moderate}`);
console.log('새로운 기준 이상 취약점은 계속 CI에서 차단됩니다.');
