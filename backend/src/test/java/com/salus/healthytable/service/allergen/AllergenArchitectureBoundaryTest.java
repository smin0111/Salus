package com.salus.healthytable.service.allergen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 6a 임시 경계: stage 7의 명시적 safety-authority migration 때 allowlist를 변경한다.
 * JDK source scan이며 Java parser/전이 의존성 분석기가 아니다. reflection/wrapper로 우회하지 않는다.
 */
class AllergenArchitectureBoundaryTest {
    private static final String INTERNAL_PACKAGE = "com.salus.healthytable.service.allergen";
    private static final Path INTERNAL_PATH = Path.of("com/salus/healthytable/service/allergen");
    private static final List<String> PROTECTED_TYPES = List.of(
            "AllergenEvidence", "AllergenFact", "DeclarationCoverageAssessment",
            "PresenceStatus", "DeclarationCoverageStatus", "NormalizedProfileAllergenTerm", "ProfileTermSource",
            "ProfileAllergenResolver", "ProfileResolution", "CompleteProfileResolution", "PartialProfileResolution",
            "ResolvedProfileAllergen", "UnresolvedProfileAllergen", "ProfileResolutionType", "ProfileUnresolvedReason",
            "ProfileResolutionShadowMetrics", "MicrometerProfileResolutionShadowMetrics");
    // 6c observation-only 예외. 이 exact caller만 void observer surface를 사용한다.
    private static final String OBSERVER = "ProfileResolutionShadowObserver";
    private static final Path OBSERVER_CALLER = Path.of("com/salus/healthytable/service/ChatSafetyContextService.java");
    private static final String QUALIFIED_NAME = "[\\p{javaJavaIdentifierPart}]+(?:\\s*\\.\\s*[\\p{javaJavaIdentifierPart}]+)*";
    private static final Pattern PACKAGE = Pattern.compile("\\bpackage\\s+(" + QUALIFIED_NAME + ")\\s*;");
    private static final Pattern IMPORT = Pattern.compile(
            "\\bimport\\s+(?:static\\s+)?(" + QUALIFIED_NAME + "(?:\\s*\\.\\s*\\*)?)\\s*;");
    // 문자열/문자/text block/주석 속 예시를 의존성으로 오인하지 않는다.
    private static final Pattern NON_CODE = Pattern.compile(
            "(?s)\"\"\".*?\"\"\"|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|/\\*.*?\\*/|//[^\\r\\n]*");

    @TempDir
    Path temporaryDirectory;

    @Test
    void productionOutputsStayInsideAllergenPackageUntilAuthorityMigration() throws Exception {
        // Maven/IDE test classes 위치에서 탐색하므로 실행 working directory에 의존하지 않는다.
        Path classes = Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
        Path sourceRoot = locateSourceRoot(classes);
        for (String type : PROTECTED_TYPES) {
            assertThat(sourceRoot.resolve(INTERNAL_PATH).resolve(type + ".java"))
                    .as("Protected model must exist; a missing source tree must not pass").isRegularFile();
        }
        List<Path> files = productionJavaFiles(sourceRoot);
        assertThat(files).isNotEmpty();
        List<String> violations = new ArrayList<>();
        for (Path file : files) {
            violations.addAll(violations(sourceRoot.relativize(file), Files.readString(file)));
        }
        assertThat(violations)
                .as("Architecture boundary violation; source root: %s. "
                        + "Protected models are internal to %s until explicit safety-authority migration.",
                        sourceRoot, INTERNAL_PACKAGE)
                .isEmpty();
    }

    @ParameterizedTest
    @MethodSource("protectedTypes")
    void detectsExplicitImportsAndFullyQualifiedUsage(String type) {
        String prefix = "package com.salus.healthytable.service; ";
        Path file = Path.of("com/salus/healthytable/service/Consumer.java");
        assertThat(violations(file, prefix + "import " + INTERNAL_PACKAGE + "." + type + "; class Consumer {}"))
                .singleElement().asString().contains(file.toString(), type, "imports", "package=com.salus.healthytable.service");
        assertThat(violations(file, prefix + "class Consumer { " + INTERNAL_PACKAGE + "." + type + " value; }"))
                .singleElement().asString().contains(type, "fully-qualified usage");
    }

    static List<String> protectedTypes() { return PROTECTED_TYPES; }

    @Test
    void onlyChatSafetyContextMayUseTheObserverBridgeAndNeverResolutionTypes() {
        String prefix = "package com.salus.healthytable.service; import " + INTERNAL_PACKAGE + ".";
        assertThat(violations(OBSERVER_CALLER, prefix + OBSERVER + ";")).isEmpty();
        assertThat(violations(OBSERVER_CALLER, "package com.salus.healthytable.service; class Caller { "
                + INTERNAL_PACKAGE + "." + OBSERVER + " observer; }")).isEmpty();
        for (String type : List.of("ProfileAllergenResolver", "ProfileResolution", "ResolvedProfileAllergen", "UnresolvedProfileAllergen")) {
            assertThat(violations(OBSERVER_CALLER, prefix + type + ";")).isNotEmpty();
        }
        assertThat(violations(Path.of("com/salus/healthytable/service/RecommendationService.java"), prefix + OBSERVER + ";"))
                .singleElement().asString().contains(OBSERVER);
        assertThat(violations(OBSERVER_CALLER, "package outside; import " + INTERNAL_PACKAGE + "." + OBSERVER + ";")).isNotEmpty();
        assertThat(violations(OBSERVER_CALLER, prefix + "*;")).isNotEmpty();
    }

    @Test
    void observerOnlyExposesVoidCollectionMethodsWithoutTypedResolutionResults() {
        var methods = java.util.Arrays.stream(ProfileResolutionShadowObserver.class.getDeclaredMethods())
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers())).toList();
        assertThat(methods).extracting(java.lang.reflect.Method::getName)
                .containsExactlyInAnyOrder("observeStoredProfile", "observeRequestProfile", "observeChatMessage");
        assertThat(methods).allSatisfy(method -> {
            assertThat(method.getReturnType()).isEqualTo(void.class);
            assertThat(method.getParameterTypes()).containsExactly(java.util.Collection.class);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "import static com.salus.healthytable.service.allergen.PresenceStatus.CONFIRMED_PRESENT;",
            "import static com.salus.healthytable.service.allergen.AllergenEvidence.EvidenceSource.*;",
            "import com.salus.healthytable.service.allergen.AllergenEvidence.EvidenceSource;",
            "import com.salus.healthytable.service.allergen.*;",
            "import com . salus . healthytable . service . allergen . AllergenFact;"
    })
    void detectsNestedStaticWildcardAndSpacedImports(String statement) {
        assertThat(violations(Path.of("Consumer.java"), "package outside;\n" + statement))
                .isNotEmpty().allSatisfy(message -> assertThat(message).contains("imports", "Consumer.java"));
    }

    @Test
    void detectsQualifiedNestedTypesAndCommentsBetweenNameSegments() {
        String code = "package outside; class Consumer { com.salus.healthytable.service. /* gap */ "
                + "allergen.AllergenEvidence.EvidenceSource source; }";
        assertThat(violations(Path.of("Consumer.java"), code))
                .singleElement().asString().contains("AllergenEvidence", "fully-qualified usage");
    }

    @Test
    void allowsOnlyTheInternalPackageAndMatchingDirectory() {
        String code = "package " + INTERNAL_PACKAGE + "; import " + INTERNAL_PACKAGE + ".AllergenFact;";
        assertThat(violations(INTERNAL_PATH.resolve("Internal.java"), code)).isEmpty();
        assertThat(violations(Path.of("elsewhere/Internal.java"), code)).isNotEmpty();
        assertThat(violations(INTERNAL_PATH.resolve("Consumer.java"), code.replace(
                "package " + INTERNAL_PACKAGE + ";", "package " + INTERNAL_PACKAGE + "evil;"))).isNotEmpty();
        assertThat(violations(INTERNAL_PATH.resolve("future/Consumer.java"), code.replace(
                "package " + INTERNAL_PACKAGE + ";", "package " + INTERNAL_PACKAGE + ".future;"))).isNotEmpty();
    }

    @Test
    void preservesLegacyUtilityImportsAndUnrelatedTypeNames() {
        String code = "package outside; import " + INTERNAL_PACKAGE + ".AllergenMatcher; import "
                + INTERNAL_PACKAGE + ".AllergenDictionary; import elsewhere.AllergenFact; class Consumer { "
                + INTERNAL_PACKAGE + ".AllergenFactHelper helper; AllergenFact unrelated; }";
        assertThat(violations(Path.of("Consumer.java"), code)).isEmpty();
    }

    @Test
    void ignoresExamplesInCommentsStringsCharactersAndTextBlocks() {
        String forbidden = INTERNAL_PACKAGE + ".AllergenFact";
        String code = "package outside; // import " + forbidden + ";\n"
                + "/* " + forbidden + " */ class Consumer { String name = \"" + forbidden + "\"; "
                + "char quote = '\"'; String block = \"\"\"\nimport " + forbidden + ";\n\"\"\"; }";
        assertThat(violations(Path.of("Consumer.java"), code)).isEmpty();
        assertThat(violations(Path.of("Consumer.java"), code + "\nclass Real { " + forbidden + " value; }"))
                .singleElement().asString().contains("AllergenFact");
    }

    @Test
    void resolvesModuleRootFromRepositoryModuleAndCompiledTestLocations() throws IOException {
        Path repository = temporaryDirectory.resolve("repository with spaces");
        Path module = repository.resolve("backend");
        Path sourceRoot = Files.createDirectories(module.resolve("src/main/java"));
        Files.writeString(module.resolve("pom.xml"), "<project/>");
        Path classes = Files.createDirectories(module.resolve("target/test-classes"));
        assertThat(locateSourceRoot(repository)).isEqualTo(sourceRoot);
        assertThat(locateSourceRoot(module)).isEqualTo(sourceRoot);
        assertThat(locateSourceRoot(classes)).isEqualTo(sourceRoot);
    }

    @Test
    void missingSourceRootFailsInsteadOfSkippingTheGuard() {
        assertThatThrownBy(() -> locateSourceRoot(temporaryDirectory))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("src/main/java");
    }

    @Test
    void scansOnlyProductionJavaFiles() throws IOException {
        Path sourceRoot = Files.createDirectories(temporaryDirectory.resolve("src/main/java"));
        Path source = Files.writeString(sourceRoot.resolve("Consumer.java"), "class Consumer {}");
        Files.writeString(sourceRoot.resolve("README.md"), "not Java");
        for (String excluded : List.of("src/test/java", "target/generated-sources", "docs")) {
            Files.writeString(Files.createDirectories(temporaryDirectory.resolve(excluded)).resolve("Example.java"), "example");
        }
        assertThat(productionJavaFiles(sourceRoot)).containsExactly(source);
    }

    private static List<Path> productionJavaFiles(Path sourceRoot) throws IOException {
        try (var files = Files.walk(sourceRoot)) {
            return files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java"))
                    .sorted().toList();
        }
    }

    private static Path locateSourceRoot(Path start) {
        for (Path current = start.toAbsolutePath().normalize(); current != null; current = current.getParent()) {
            for (Path module : List.of(current, current.resolve("backend"))) {
                Path sourceRoot = module.resolve("src/main/java");
                if (Files.isRegularFile(module.resolve("pom.xml")) && Files.isDirectory(sourceRoot)) {
                    return sourceRoot;
                }
            }
        }
        throw new IllegalStateException("Cannot locate backend src/main/java from " + start);
    }

    private static List<String> violations(Path relativeFile, String source) {
        String code = NON_CODE.matcher(source).replaceAll(" ");
        var packageMatcher = PACKAGE.matcher(code);
        String packageName = packageMatcher.find() ? compactName(packageMatcher.group(1)) : "<default>";
        if (INTERNAL_PACKAGE.equals(packageName) && INTERNAL_PATH.equals(relativeFile.getParent())) {
            return List.of();
        }
        List<String> found = new ArrayList<>();
        List<String> inspectedTypes = new ArrayList<>(PROTECTED_TYPES);
        // Observer 자체는 한 caller에만 허용하며 resolver/output/recorder 경계는 열지 않는다.
        if (!OBSERVER_CALLER.equals(relativeFile) || !packageName.equals("com.salus.healthytable.service")) {
            inspectedTypes.add(OBSERVER);
        }
        var imports = IMPORT.matcher(code);
        while (imports.find()) {
            String imported = compactName(imports.group(1));
            for (String type : inspectedTypes) {
                String qualified = INTERNAL_PACKAGE + "." + type;
                if (imported.equals(qualified) || imported.startsWith(qualified + ".")
                        || imported.equals(INTERNAL_PACKAGE + ".*")) {
                    found.add(violation(relativeFile, packageName, type, "imports " + imported));
                }
            }
        }
        String body = IMPORT.matcher(code).replaceAll(" ");
        for (String type : inspectedTypes) {
            String qualifiedPattern = String.join("\\s*\\.\\s*", (INTERNAL_PACKAGE + "." + type).split("\\."));
            Pattern reference = Pattern.compile("(?<![\\p{javaJavaIdentifierPart}.])" + qualifiedPattern
                    + "(?!\\p{javaJavaIdentifierPart})");
            if (reference.matcher(body).find()) {
                found.add(violation(relativeFile, packageName, type, "fully-qualified usage"));
            }
        }
        return found;
    }

    private static String compactName(String name) {
        return name.replaceAll("\\s+", "");
    }

    private static String violation(Path file, String packageName, String type, String access) {
        return "Architecture boundary violation: " + file + " [package=" + packageName + "] " + access
                + "; " + type + " is internal to " + INTERNAL_PACKAGE
                + " until explicit safety-authority migration.";
    }
}
