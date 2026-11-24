package com.campussphere.backend.service;

import java.util.Locale;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.campussphere.backend.dto.LoginRequest;
import com.campussphere.backend.dto.LoginResponse;
import com.campussphere.backend.dto.RegisterRequest;
import com.campussphere.backend.entity.User;
import com.campussphere.backend.repository.RoleRepository;
import com.campussphere.backend.repository.UserRepository;
import com.campussphere.backend.security.AccountAccessPolicy;
import com.campussphere.backend.security.ApiError;
import com.campussphere.backend.security.JwtService;

@Service
public class AuthService {
    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AccountAccessPolicy accessPolicy;
    private final FacultyAccessService faculty;

    public AuthService(UserRepository users, RoleRepository roles, PasswordEncoder passwordEncoder,
                       JwtService jwtService, AccountAccessPolicy accessPolicy, FacultyAccessService faculty) {
        this.users = users;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.accessPolicy = accessPolicy;
        this.faculty = faculty;
    }

    @Transactional
    public String register(RegisterRequest request) {
        String email = request.getEmail().trim().toLowerCase(Locale.ROOT);
        String requestedRole = request.getRole().trim().toUpperCase(Locale.ROOT);
        String phone = request.getPhone();
        if (!requestedRole.equals("STUDENT") && !requestedRole.equals("FACULTY")) {
            throw ApiError.REGISTRATION_NOT_ALLOWED.exception();
        }
        if (users.existsByEmail(email) || (phone != null && users.existsByPhone(phone))) {
            throw ApiError.ACCOUNT_ALREADY_EXISTS.exception();
        }
        var user = new User();
        user.setFullName(request.getFullName().trim());
        user.setEmail(email);
        user.setPhone(phone);
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        if (requestedRole.equals("STUDENT")) {
            user.setRoles(Set.of(roles.findByName("STUDENT").orElseThrow()));
        }
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            if (e.getMostSpecificCause() instanceof java.sql.SQLException sql
                    && "23505".equals(sql.getSQLState())) {
                throw ApiError.ACCOUNT_ALREADY_EXISTS.exception();
            }
            throw e;
        }
        if (requestedRole.equals("FACULTY")) {
            faculty.openInitialRequest(user.getId());
        }
        return "Registration successful";
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        String email = request.getEmail().trim().toLowerCase(Locale.ROOT);
        User user = users.findByEmail(email).orElseThrow(ApiError.INVALID_AUTHENTICATION::exception);
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())
                || !Boolean.TRUE.equals(user.getIsActive())) {
            throw ApiError.INVALID_AUTHENTICATION.exception();
        }
        String token = jwtService.generateToken(user.getEmail(), user.getId());
        return new LoginResponse(token, user.getId(), user.getFullName(), user.getEmail(),
                accessPolicy.account(user).roles());
    }
}
