package com.campussphere.backend.security;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.campussphere.backend.entity.User;
import com.campussphere.backend.repository.UserRepository;

@Service
public class CustomUserDetailsService implements UserDetailsService {
    private final UserRepository users;
    private final AccountAccessPolicy accessPolicy;

    public CustomUserDetailsService(UserRepository users, AccountAccessPolicy accessPolicy) {
        this.users = users;
        this.accessPolicy = accessPolicy;
    }

    public User loadAccount(Long userId) {
        return users.findById(userId)
                .orElseThrow(() -> new UsernameNotFoundException("Account no longer exists"));
    }

    @Override
    public UserDetails loadUserByUsername(String email) {
        var user = users.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("Account no longer exists"));
        return org.springframework.security.core.userdetails.User.withUsername(user.getEmail())
                .password(user.getPassword())
                .authorities(accessPolicy.account(user).authorities())
                .disabled(!Boolean.TRUE.equals(user.getIsActive()))
                .build();
    }
}
