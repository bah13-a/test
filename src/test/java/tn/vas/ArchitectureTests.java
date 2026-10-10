package tn.vas;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleName;
import static com.tngtech.archunit.core.domain.properties.CanBeAnnotated.Predicates.annotatedWith;
import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

/**
 * Règles d'architecture CQRS, vérifiées à chaque build :
 *  - côté requête (tn.vas.query) : aucune écriture, aucun appel aux services de commande, uniquement des GET ;
 *  - côté commande (service, web, domain, repo) : ne connaît pas les projections, communique par événements ;
 *  - projections : ne dépendent ni des services de commande, ni des contrôleurs, ni des requêtes.
 */
class ArchitectureTests {
    static JavaClasses classes;

    @BeforeAll
    static void load() {
        classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("tn.vas");
    }

    @Test
    void queryHasNoDependencyOnCommandServicesExceptAuditAndTextUtilities() {
        noClasses().that().resideInAPackage("tn.vas.query..")
                .should().dependOnClassesThat(resideInAPackage("tn.vas.service..").and(not(simpleName("AuditService"))).and(not(simpleName("Text"))))
                .because("le côté requête ne passe jamais par la logique d'écriture (seule la trace d'audit des accès/exports est tolérée)").check(classes);
    }

    @Test
    void queryNeverWrites() {
        var writes = com.tngtech.archunit.base.DescribedPredicate.describe("une écriture en base (dépôt, JdbcTemplate ou EntityManager)",
                (com.tngtech.archunit.core.domain.AccessTarget t) -> {
                    var owner = t.getOwner();
                    String n = t.getName();
                    boolean repo = owner.isAssignableTo(org.springframework.data.repository.Repository.class) && n.matches("save.*|delete.*|flush|persist|merge|remove");
                    boolean jdbc = owner.isAssignableTo(org.springframework.jdbc.core.JdbcOperations.class) && n.matches("update|batchUpdate|execute");
                    boolean em = owner.isAssignableTo(jakarta.persistence.EntityManager.class) && n.matches("persist|merge|remove|flush");
                    return repo || jdbc || em;
                });
        noClasses().that().resideInAPackage("tn.vas.query..")
                .should().callMethodWhere(com.tngtech.archunit.core.domain.JavaCall.Predicates.target(writes))
                .because("le côté requête est en lecture seule").check(classes);
    }

    @Test
    void queryControllersExposeOnlyGet() {
        noMethods().that().areDeclaredInClassesThat().resideInAPackage("tn.vas.query..")
                .should().beAnnotatedWith(PostMapping.class).orShould().beAnnotatedWith(PutMapping.class)
                .orShould().beAnnotatedWith(PatchMapping.class).orShould().beAnnotatedWith(DeleteMapping.class)
                .because("toute modification passe par une commande").check(classes);
    }

    @Test
    void commandSideDoesNotKnowTheProjections() {
        noClasses().that().resideInAnyPackage("tn.vas.service..", "tn.vas.web..", "tn.vas.domain..", "tn.vas.repo..", "tn.vas.gateway..")
                .should().dependOnClassesThat().resideInAPackage("tn.vas.projection..")
                .because("le côté commande publie des événements, il n'attend ni n'appelle les projections").check(classes);
    }

    @Test
    void projectionsDependOnEventsOnly() {
        noClasses().that().resideInAPackage("tn.vas.projection..")
                .should().dependOnClassesThat().resideInAnyPackage("tn.vas.service..", "tn.vas.web..", "tn.vas.query..")
                .because("une projection transforme des événements en lignes de lecture, sans logique métier").check(classes);
    }

    @Test
    void domainEventsAreOnlyPublishedByTheCommandSide() {
        noClasses().that().resideInAnyPackage("tn.vas.query..", "tn.vas.projection..")
                .should().dependOnClassesThat().areAssignableTo(org.springframework.context.ApplicationEventPublisher.class)
                .because("seul le côté commande publie des événements de domaine").check(classes);
    }
}
