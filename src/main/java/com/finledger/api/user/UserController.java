package com.finledger.api.user;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Handles user registration ({@code POST /users}).
 */
@RestController
@RequestMapping("/users")
public class UserController {

    public record RegisterUserRequest(String username) {
    }

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    public ResponseEntity<UserService.RegisteredUser> register(@RequestBody RegisterUserRequest request) {
        String username = (request != null) ? request.username() : null;
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.registerUser(username));
    }
}
