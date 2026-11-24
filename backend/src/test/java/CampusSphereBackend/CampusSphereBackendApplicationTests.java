package CampusSphereBackend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.campussphere.backend.CampusSphereBackendApplication;
import com.campussphere.backend.entity.User;
import com.campussphere.backend.repository.RoleRepository;
import com.campussphere.backend.repository.UserRepository;

@SpringBootTest(classes = CampusSphereBackendApplication.class)
@ActiveProfiles("test")
class CampusSphereBackendApplicationTests {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RoleRepository roles;

    @Autowired
    private UserRepository users;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    @Transactional
    void migrationsAndRepositoryMappingsWorkOnPostgres19() {
        assertEquals("campus_sphere_test", jdbc.queryForObject(
                "SELECT current_database()", String.class));
        Integer serverVersion = jdbc.queryForObject(
                "SELECT current_setting('server_version_num')::integer", Integer.class);
        assertNotNull(serverVersion);
        assertEquals(19, serverVersion / 10000);
        assertEquals(List.of("1", "2", "3", "4", "5", "6", "7"), jdbc.queryForList(
                "SELECT version FROM public.flyway_schema_history WHERE success ORDER BY installed_rank",
                String.class));

        assertEquals(5, roles.count());
        var student = roles.findByName("STUDENT").orElseThrow();
        assertTrue(roles.findByName("FACULTY").isPresent());
        assertTrue(roles.findByName("ADMIN").isPresent());
        assertTrue(roles.findByName("SUPER_ADMIN").isPresent());
        assertTrue(roles.findByName("COLLEGE_ADMIN").isPresent());

        var user = new User();
        user.setFullName("Migration Test");
        user.setEmail("migration-test@example.invalid");
        user.setPhone("0123456789");
        user.setPassword(passwordEncoder.encode("test-only-password"));
        user.setRoles(Set.of(student));

        Long userId = users.saveAndFlush(user).getId();
        assertNotNull(userId);
        entityManager.clear();

        assertTrue(users.existsByEmail(user.getEmail()));
        assertTrue(users.existsByPhone(user.getPhone()));
        var reloaded = users.findByEmail(user.getEmail()).orElseThrow();
        assertEquals(userId, reloaded.getId());
        assertEquals(Set.of("STUDENT"), reloaded.getRoles().stream()
                .map(role -> role.getName())
                .collect(java.util.stream.Collectors.toSet()));
        assertNotNull(reloaded.getCreatedAt());
        assertNotNull(reloaded.getUpdatedAt());
    }
}
