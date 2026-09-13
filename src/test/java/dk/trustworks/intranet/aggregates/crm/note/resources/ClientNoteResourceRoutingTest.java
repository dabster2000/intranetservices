package dk.trustworks.intranet.aggregates.crm.note.resources;

import dk.trustworks.intranet.aggregates.crm.note.dto.ClientNoteRequest;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Locks the routing and the scopes of the note endpoints.
 *
 * <p>Plain JUnit, no {@code @QuarkusTest}: this must run in the DB-free fast tier that gates
 * every deploy, because everything it protects fails silently. A resource rooted at
 * {@code /} or nested under a prefix another class owns answers 404 "Unable to find matching
 * target resource method" — RESTEasy Reactive picks the resource CLASS by its class-level
 * {@code @Path} before it looks at any method — and {@code ./mvnw compile} does not catch a
 * colliding path at all.
 *
 * <p>The composed paths are the BFF contract
 * ({@code trustworks-intranet-v2/src/app/api/client-notes/**}) and must not move.
 */
class ClientNoteResourceRoutingTest {

    private static final Class<?> NOTES = ClientNoteResource.class;

    /** {@code /client-notes} is a prefix no other resource class owns. */
    @Test
    void isRootedAtClientNotes() {
        assertEquals("/client-notes", classPath(NOTES));
    }

    /**
     * The trap itself: rooted at {@code /}, or nested under {@code /clients} — which
     * {@code ClientResource} owns — or under {@code /accounts}, this class would be
     * invisible and every method would 404.
     */
    @Test
    void isNotRootedAtSlash() {
        assertNotEquals("/", classPath(NOTES));
    }

    @Test
    void createIsPostOnTheClassRoot() {
        Method create = method(NOTES, "create", ClientNoteRequest.class);
        assertVerb(create, POST.class);
        assertNull(create.getAnnotation(Path.class),
                "create must sit on the class root — POST /client-notes is the BFF contract");
    }

    /**
     * Removal addresses a note by its own uuid rather than by account and note, which is why
     * this is a class of its own and not a method on {@code AccountResource}: a
     * {@code /accounts/{clientUuid}/notes/{uuid}} shape carries a second key that nothing
     * validates against the note it names.
     */
    @Test
    void deleteAddressesANoteByItsOwnUuid() {
        Method delete = method(NOTES, "delete", String.class);
        assertVerb(delete, DELETE.class);
        assertEquals("/client-notes/{uuid}", fullPath(delete));
    }

    /**
     * Reading is {@code accounts:read}, the key the timeline these notes appear in already
     * uses, held by every employee. Writing is {@code accounts:write} — SALES/PARTNER/ADMIN
     * (V585:223-225) — and not {@code signals:write}, which role USER holds: letting every
     * employee put free text about a third party on the most-read surface of the account page
     * is a product decision nobody has taken. {@code crm:read} is a read key and must never
     * authorize a write.
     */
    @Test
    void readsAreAccountsReadAndEveryWriteIsAccountsWrite() {
        assertEquals(Set.of("accounts:read"), classScopes(NOTES));
        assertNull(method(NOTES, "list", String.class).getAnnotation(RolesAllowed.class),
                "list inherits the class-level accounts:read");
        assertEquals(Set.of("accounts:write"),
                methodScopes(method(NOTES, "create", ClientNoteRequest.class)));
        assertEquals(Set.of("accounts:write"),
                methodScopes(method(NOTES, "delete", String.class)));
    }

    /**
     * The request record must not carry an author or a timestamp. Adding one would let a
     * caller put a colleague's name on a line they never wrote, by putting it in the body —
     * the mass-assignment rule, enforced here rather than trusted, because nothing else in
     * the fast tier would notice.
     */
    @Test
    void requestCannotForgeAuthorshipOrTimestamps() {
        Set<String> components = Set.of(ClientNoteRequest.class.getRecordComponents())
                .stream()
                .map(java.lang.reflect.RecordComponent::getName)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("clientUuid", "text"), components,
                "ClientNoteRequest must expose only what a caller may decide — "
                        + "the author and the timestamp are derived server-side");
    }

    private static String classPath(Class<?> type) {
        Path path = type.getAnnotation(Path.class);
        assertNotNull(path, type.getSimpleName() + " must carry a class-level @Path");
        return path.value();
    }

    private static String fullPath(Method m) {
        Path path = m.getAnnotation(Path.class);
        assertNotNull(path, m.getName() + " must carry a method-level @Path");
        return classPath(m.getDeclaringClass()) + path.value();
    }

    private static Set<String> classScopes(Class<?> type) {
        RolesAllowed roles = type.getAnnotation(RolesAllowed.class);
        assertNotNull(roles, type.getSimpleName() + " must be scope-gated at the class level");
        return Set.of(roles.value());
    }

    private static Set<String> methodScopes(Method m) {
        RolesAllowed roles = m.getAnnotation(RolesAllowed.class);
        assertNotNull(roles, m.getName() + " must override the class read scope with a write scope");
        return Set.of(roles.value());
    }

    private static void assertVerb(Method m, Class<? extends Annotation> verb) {
        assertNotNull(m.getAnnotation(verb), m.getName() + " must be @" + verb.getSimpleName());
    }

    private static Method method(Class<?> type, String name, Class<?>... params) {
        try {
            return type.getDeclaredMethod(name, params);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(type.getSimpleName() + "." + name + " is missing; the BFF contract references it", e);
        }
    }
}
