package com.salus.healthytable.service;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Separates consumption and interaction questions from requests to make a dish. */
record FoodSafetyQuestion(Kind kind, String foodPhrase, String foodName, String declaredMedication) {

    enum Kind { CONSUMPTION, MEDICATION_INTERACTION }

    private static final String PERMISSION_OUTCOME =
            "(?:안\\s*)?(?:돼|될|되(?:나요|나|니|는지|겠|(?=\\s|[?？.!]|$))|괜찮|안전|위험|문제\\s*없)";
    private static final Pattern PERMISSION = Pattern.compile(
            "(?:먹어|마셔|먹여|드셔|섭취해)도\\s*(?:" + PERMISSION_OUTCOME + "|(?=[?？]|$))"
                    + "|(?:먹으면|마시면|먹이면|드시면|섭취하면)\\s*" + PERMISSION_OUTCOME
                    + "|먹을\\s*수\\s*있(?:어|나|을까|나요)");
    private static final Pattern MEDICATION_CONTEXT = Pattern.compile(
            "복용|상호\\s*작용|(?:^|\\s)약(?:이?랑|하고|과|을|은|도|\\s|$)");
    private static final Pattern MEDICATION_DECLARATION = Pattern.compile(
            "^(?:(?:나|나는|저|저는|제가)\\s+)?([\\p{L}\\p{N}-]{2,60})\\s+복용\\s*중(?:인데|이지만|이고)\\s+");
    private static final Pattern INTERACTION = Pattern.compile(
            "상호\\s*작용|(?:같이|함께)\\s*(?:먹|마시|마셔|복용|섭취|드셔|드시면)");
    // Keep lists of foods intact, including lists separated by commas.
    private static final Pattern CLAUSE_BREAK = Pattern.compile("(?:인데|는데|은데|지만|있고|\\.)\\s+");
    private static final Pattern TRAILING_PARTICLE = Pattern.compile("(?:은|는|을|를|도|만)$");
    private static final Pattern SINGLE_FOOD = Pattern.compile("[\\p{L}\\p{N}-]+");
    private static final Pattern JOINED_TERMS = Pattern.compile("[가-힣]+(?:이랑|랑|하고)[가-힣]+");

    boolean canUseProfileMedication() {
        return kind == Kind.CONSUMPTION && SINGLE_FOOD.matcher(foodName).matches()
                && !JOINED_TERMS.matcher(foodName).find();
    }

    static Optional<FoodSafetyQuestion> parse(String message) {
        if (message == null || message.isBlank()) {
            return Optional.empty();
        }
        boolean medicationContext = MEDICATION_CONTEXT.matcher(message).find();
        Matcher permission = PERMISSION.matcher(message);
        if (permission.find()) {
            String beforeQuestion = message.substring(0, permission.start()).strip();
            String[] clauses = CLAUSE_BREAK.split(beforeQuestion, -1);
            String phrase = clauses[clauses.length - 1].strip();
            String name = TRAILING_PARTICLE.matcher(phrase).replaceAll("").strip();
            return Optional.of(new FoodSafetyQuestion(
                    medicationContext ? Kind.MEDICATION_INTERACTION : Kind.CONSUMPTION,
                    phrase.isBlank() ? message : phrase, name, declaredMedication(beforeQuestion)));
        }
        if (medicationContext && INTERACTION.matcher(message).find()) {
            return Optional.of(new FoodSafetyQuestion(Kind.MEDICATION_INTERACTION, message, "", ""));
        }
        return Optional.empty();
    }

    private static String declaredMedication(String prefix) {
        Matcher declaration = MEDICATION_DECLARATION.matcher(prefix);
        if (!declaration.find()) {
            return "";
        }
        String name = declaration.group(1);
        return "약을".equals(name) ? "" : name;
    }
}
