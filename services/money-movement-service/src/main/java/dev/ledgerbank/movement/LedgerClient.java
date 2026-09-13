package dev.ledgerbank.movement;

import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
class ServiceTokenClient {
    private final RestClient rest;private final String clientId;private final String secret;private String token;private Instant refreshAt=Instant.EPOCH;
    ServiceTokenClient(RestClient.Builder builder,@Value("${ledgerbank.identity-url}")String url,@Value("${ledgerbank.service-client-id}")String clientId,@Value("${ledgerbank.service-client-secret}")String secret){this.rest=builder.baseUrl(url).build();this.clientId=clientId;this.secret=secret;}
    synchronized String token(){if(Instant.now().isBefore(refreshAt))return token;TokenResponse response=rest.post().uri("/api/v1/auth/service-token").body(Map.of("clientId",clientId,"clientSecret",secret)).retrieve().body(TokenResponse.class);if(response==null)throw new IllegalStateException("Identity returned no service token");token=response.accessToken;refreshAt=Instant.now().plusSeconds(Math.max(30,response.expiresIn-30));return token;}
    record TokenResponse(String accessToken,long expiresIn){}
}

@Component
class LedgerClient {
    private final RestClient rest;private final ServiceTokenClient tokens;
    LedgerClient(RestClient.Builder builder,@Value("${ledgerbank.ledger-url}")String url,ServiceTokenClient tokens){this.rest=builder.baseUrl(url).build();this.tokens=tokens;}
    Journal post(UUID transferId,UUID source,String destination,long amount,UUID user,String correlationId){return rest.post().uri("/internal/v1/postings/internal-transfer").header(HttpHeaders.AUTHORIZATION,"Bearer "+tokens.token()).header("X-Correlation-ID",correlationId).body(new Command(transferId,source,destination,amount,user)).retrieve().body(Journal.class);}
    Journal find(String reference){return rest.get().uri(uri->uri.path("/internal/v1/journals/by-reference").queryParam("reference",reference).build()).header(HttpHeaders.AUTHORIZATION,"Bearer "+tokens.token()).retrieve().body(Journal.class);}
    record Command(UUID commandId,UUID sourceAccountId,String destinationAccountNumber,long amountMinor,UUID initiatedBySubject){}
    record Journal(UUID journalId,String businessReference,String journalType,String description,String correlationId,Instant bookedAt){}
}

