package com.campussphere.backend.security;

import java.io.IOException;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.filter.OncePerRequestFilter;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final CustomUserDetailsService accounts;
    private final AccountAccessPolicy accessPolicy;
    private final ApiSecurityErrors errors;

    public JwtAuthenticationFilter(JwtService jwtService, CustomUserDetailsService accounts,
                                   AccountAccessPolicy accessPolicy, ApiSecurityErrors errors) {
        this.jwtService = jwtService;
        this.accounts = accounts;
        this.accessPolicy = accessPolicy;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            try {
                var claims = jwtService.parseToken(authorization.substring(7));
                Long userId = claims.get("userId", Long.class);
                if (userId == null || claims.getExpiration() == null || claims.getSubject() == null) {
                    reject(response, ApiError.INVALID_AUTHENTICATION);
                    return;
                }
                var user = accounts.loadAccount(userId);
                if (!Boolean.TRUE.equals(user.getIsActive()) || !user.getEmail().equals(claims.getSubject())) {
                    reject(response, ApiError.INVALID_AUTHENTICATION);
                    return;
                }
                var account = accessPolicy.account(user);
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        account, null, account.authorities()));
                SecurityContextHolder.setContext(context);
            } catch (JwtException | IllegalArgumentException | UsernameNotFoundException e) {
                reject(response, ApiError.INVALID_AUTHENTICATION);
                return;
            } catch (TransientDataAccessException | DataAccessResourceFailureException
                     | RecoverableDataAccessException | CannotCreateTransactionException e) {
                reject(response, ApiError.DATABASE_UNAVAILABLE);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, ApiError error) throws IOException {
        SecurityContextHolder.clearContext();
        errors.write(response, error);
    }
}
