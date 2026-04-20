package org.example.rentoza.auth;

import org.example.rentoza.security.supabase.SupabaseAuthClient;
import org.example.rentoza.security.supabase.SupabaseUserMapping;
import org.example.rentoza.security.supabase.SupabaseUserMappingRepository;
import org.example.rentoza.user.AuthProvider;
import org.example.rentoza.user.OwnerType;
import org.example.rentoza.user.RegistrationStatus;
import org.example.rentoza.user.Role;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Component
@Profile("dev")
@ConditionalOnProperty(name = "app.auth.local-seed.enabled", havingValue = "true", matchIfMissing = true)
public class LocalAuthSeedInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalAuthSeedInitializer.class);

    private final SupabaseAuthClient supabaseAuthClient;
    private final UserRepository userRepository;
    private final SupabaseUserMappingRepository mappingRepository;

    @Value("${app.auth.local-seed.user.email:demo.user.local@rentoza.rs}")
    private String userEmail;

    @Value("${app.auth.local-seed.user.password:DemoUser123!}")
    private String userPassword;

    @Value("${app.auth.local-seed.owner.email:demo.owner.local@rentoza.rs}")
    private String ownerEmail;

    @Value("${app.auth.local-seed.owner.password:DemoOwner123!}")
    private String ownerPassword;

    @Value("${app.auth.local-seed.admin.email:demo.admin.local@rentoza.rs}")
    private String adminEmail;

    @Value("${app.auth.local-seed.admin.password:DemoAdmin123!}")
    private String adminPassword;

    public LocalAuthSeedInitializer(
            SupabaseAuthClient supabaseAuthClient,
            UserRepository userRepository,
            SupabaseUserMappingRepository mappingRepository
    ) {
        this.supabaseAuthClient = supabaseAuthClient;
        this.userRepository = userRepository;
        this.mappingRepository = mappingRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Lokalni auth seed: priprema demo naloga je pokrenuta");

        seedAccount(Role.USER, userEmail, userPassword, "Demo", "Korisnik");
        seedAccount(Role.OWNER, ownerEmail, ownerPassword, "Demo", "Vlasnik");
        seedAccount(Role.ADMIN, adminEmail, adminPassword, "Demo", "Admin" );

        log.info("Lokalni auth seed: demo nalozi su spremni");
    }

    private void seedAccount(Role role, String rawEmail, String password, String firstName, String lastName) {
        if (!hasText(rawEmail) || !hasText(password)) {
            log.warn("Lokalni auth seed preskocen za ulogu {}: email ili lozinka nisu definisani", role);
            return;
        }

        String email = normalizeEmail(rawEmail);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("firstName", firstName);
        metadata.put("lastName", lastName);
        metadata.put("role", role.name());

        SupabaseAuthClient.SupabaseUser supabaseUser = supabaseAuthClient.getUserByEmail(email);
        if (supabaseUser == null) {
            try {
                supabaseUser = supabaseAuthClient.createUser(email, password, metadata);
            } catch (SupabaseAuthClient.SupabaseAuthException ex) {
                if (!isEmailAlreadyExists(ex)) {
                    throw ex;
                }

                log.info("Lokalni auth seed: nalog vec postoji u Supabase za {}. Nastavljam sa osvezavanjem podataka.", email);
                supabaseUser = supabaseAuthClient.getUserByEmail(email);
            }
        }

        if (supabaseUser == null || supabaseUser.getId() == null) {
            log.error("Lokalni auth seed neuspesan za ulogu {}: Supabase korisnik nije dostupan", role);
            return;
        }

        supabaseAuthClient.updateUserPassword(supabaseUser.getId(), password);
        supabaseAuthClient.updateUserMetadata(supabaseUser.getId(), metadata);
        supabaseAuthClient.confirmUserEmail(supabaseUser.getId());

        upsertLocalUser(role, email, firstName, lastName, supabaseUser.getId());
        log.info("Lokalni auth seed spreman za ulogu {} ({})", role, email);
    }

    private static boolean isEmailAlreadyExists(SupabaseAuthClient.SupabaseAuthException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof HttpClientErrorException httpEx) {
            String responseBody = httpEx.getResponseBodyAsString();
            return responseBody != null && responseBody.contains("email_exists");
        }
        return false;
    }

    private void upsertLocalUser(Role role, String email, String firstName, String lastName, UUID supabaseId) {
        User user = userRepository.findByEmail(email).orElseGet(User::new);

        user.setEmail(email);
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setPassword("SUPABASE_AUTH");
        user.setAuthProvider(AuthProvider.SUPABASE);
        user.setAuthUid(supabaseId);
        user.setRole(role);
        user.setEnabled(true);
        user.setLocked(false);
        user.setBanned(false);
        user.setRegistrationStatus(RegistrationStatus.ACTIVE);
        if (role == Role.OWNER && user.getOwnerType() == null) {
            user.setOwnerType(OwnerType.INDIVIDUAL);
        }
        user.resetFailedLoginAttempts();

        User saved = userRepository.save(user);
        syncMapping(supabaseId, saved.getId());
    }

    private void syncMapping(UUID supabaseId, Long rentozaUserId) {
        mappingRepository.findByRentozaUserId(rentozaUserId)
                .filter(existing -> !existing.getSupabaseId().equals(supabaseId))
                .ifPresent(mappingRepository::delete);

        mappingRepository.findById(supabaseId)
                .ifPresentOrElse(
                        existing -> {
                            if (!existing.getRentozaUserId().equals(rentozaUserId)) {
                                existing.setRentozaUserId(rentozaUserId);
                                mappingRepository.save(existing);
                            }
                        },
                        () -> mappingRepository.save(SupabaseUserMapping.create(supabaseId, rentozaUserId))
                );
    }

    private static String normalizeEmail(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
