package dev.ledgerbank.gateway;
import java.util.Map;import org.springframework.web.bind.annotation.*;
@RestController class CsrfController{@GetMapping("/api/v1/auth/csrf")Map<String,String>csrf(){return Map.of("status","READY");}}
