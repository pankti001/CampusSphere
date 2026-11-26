package com.campussphere.backend.security;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    FilterRegistrationBean<JwtAuthenticationFilter> jwtServletRegistration(JwtAuthenticationFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter filter,
                                                   ApiSecurityErrors errors) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, failure) ->
                                errors.write(response, ApiError.INVALID_AUTHENTICATION))
                        .accessDeniedHandler((request, response, failure) -> {
                            var authentication = SecurityContextHolder.getContext().getAuthentication();
                            boolean verificationRequired = authentication != null
                                    && authentication.getPrincipal() instanceof AccountAccessPolicy.Account account
                                    && account.studentRestricted()
                                    && request.getRequestURI().startsWith(request.getContextPath() + "/api/student/");
                            errors.write(response, verificationRequired
                                    ? ApiError.VERIFICATION_REQUIRED : ApiError.ACCESS_DENIED);
                        }))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/college-registration-requests", "/api/auth/register", "/api/auth/login",
                                "/api/auth/college-admin-invitations/accept").permitAll()
                        .requestMatchers("/api/platform/college-registration-requests", "/api/platform/college-registration-requests/**", "/api/platform/overview", "/api/platform/colleges", "/api/platform/colleges/**").hasRole("SUPER_ADMIN")
                        .requestMatchers("/api/student/**").hasRole("STUDENT")
                        .requestMatchers("/api/faculty/**").hasRole("FACULTY")
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/college/faculty", "/api/college/faculty/**",
                                "/api/college/faculty-requests",
                                "/api/college/faculty-requests/*/decision").hasRole("COLLEGE_ADMIN")
                        .requestMatchers("/api/college/materials", "/api/college/materials/**", "/api/college/attendance/**").hasRole("COLLEGE_ADMIN")
                        .requestMatchers("/api/college/announcements", "/api/college/announcements/**").hasRole("COLLEGE_ADMIN")
                        .requestMatchers("/api/college/timetables", "/api/college/timetables/**",
                                "/api/college/classrooms", "/api/college/classrooms/**").hasRole("COLLEGE_ADMIN")
                        .requestMatchers("/api/college/programs/*/academic-years/*/divisions",
                                "/api/college/programs/*/academic-years/*/divisions/*",
                                "/api/college/programs/*/semesters/*/subjects",
                                "/api/college/programs/*/semesters/*/subjects/*").hasRole("COLLEGE_ADMIN")
                        .requestMatchers("/api/college/academic-years", "/api/college/academic-years/*",
                                "/api/college/programs/*/academic-years/*/semesters",
                                "/api/college/programs/*/academic-years/*/semesters/*").hasRole("COLLEGE_ADMIN")
                        .requestMatchers("/api/college/departments", "/api/college/departments/*", "/api/college/departments/*/programs", "/api/college/departments/*/programs/*").hasRole("COLLEGE_ADMIN")
                        .requestMatchers("/api/college/students", "/api/college/students/**",
                                "/api/college/student-affiliation-requests",
                                "/api/college/student-affiliation-requests/*/decision").hasRole("COLLEGE_ADMIN")
                        .requestMatchers("/api/auth/status", "/api/auth/faculty/reapply",
                                "/api/auth/student-affiliation").authenticated()
                        .anyRequest().denyAll())
                .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
