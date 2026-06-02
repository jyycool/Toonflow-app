package com.toonflow.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
public class SocketIoController {

    @GetMapping("/socket.io/")
    public ResponseEntity<String> polling(
            @RequestParam(defaultValue = "4") int EIO,
            @RequestParam String transport,
            @RequestParam(required = false) String sid) {

        if ("polling".equals(transport) && sid == null) {
            String newSid = UUID.randomUUID().toString().replace("-", "").substring(0, 20);
            String resp = "0{\"sid\":\"" + newSid + "\",\"upgrades\":[\"websocket\"],\"pingInterval\":25000,\"pingTimeout\":20000,\"maxPayload\":1000000}";
            String body = resp.length() + ":" + resp;
            return ResponseEntity.ok()
                    .header("Content-Type", "text/plain; charset=UTF-8")
                    .header("Access-Control-Allow-Origin", "*")
                    .body(body);
        }

        if ("polling".equals(transport) && sid != null) {
            return ResponseEntity.ok()
                    .header("Content-Type", "text/plain; charset=UTF-8")
                    .header("Access-Control-Allow-Origin", "*")
                    .body("2:40");
        }

        return ResponseEntity.ok("ok");
    }

    @PostMapping("/socket.io/")
    public ResponseEntity<String> pollingPost(
            @RequestParam(required = false) String transport,
            @RequestParam(required = false) String sid,
            @RequestBody(required = false) String body) {
        return ResponseEntity.ok()
                .header("Content-Type", "text/plain; charset=UTF-8")
                .header("Access-Control-Allow-Origin", "*")
                .body("ok");
    }
}
