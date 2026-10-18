package com.ziyadsamhaoui.messagingattachmentservice.security;

import com.ziyadsamhaoui.messagingattachmentservice.exception.UnauthenticatedRequestException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class CurrentUserProvider {

    public UUID requireUserId() {
        Jwt jwt = currentJwt()
                .orElseThrow(() -> new UnauthenticatedRequestException("A valid bearer token is required"));

        String subject = jwt.getSubject();
        if (!StringUtils.hasText(subject)) {
            throw new UnauthenticatedRequestException("The access token does not identify a user");
        }
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException exception) {
            throw new UnauthenticatedRequestException("The access token subject is not a user identifier");
        }
    }

    private Optional<Jwt> currentJwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication
                && jwtAuthentication.isAuthenticated()) {
            return Optional.of(jwtAuthentication.getToken());
        }
        return Optional.empty();
    }
}
