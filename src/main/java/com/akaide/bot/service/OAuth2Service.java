package com.akaide.bot.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Service
public class OAuth2Service {

    @Value("${google.client.id}")
    private String clientId;

    @Value("${google.redirect.uri:http://localhost:8080/api/oauth2/callback}")
    private String redirectUri;

    public String getAuthorizationUrl(String discordUserId) {
        String scope = "https://www.googleapis.com/auth/calendar";
        String encodedScope = URLEncoder.encode(scope, StandardCharsets.UTF_8);
        String encodedRedirect = URLEncoder.encode(redirectUri, StandardCharsets.UTF_8);

        return "https://accounts.google.com/o/oauth2/v2/auth?" +
                "client_id=" + clientId +
                "&redirect_uri=" + encodedRedirect +
                "&response_type=code" +
                "&scope=" + encodedScope +
                "&access_type=offline" +
                "&prompt=consent" +
                "&state=" + discordUserId;
    }
}