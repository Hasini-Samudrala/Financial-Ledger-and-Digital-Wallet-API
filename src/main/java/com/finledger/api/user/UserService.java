package com.finledger.api.user;

import com.finledger.api.db.SqliteTransactionExecutor;
import com.finledger.api.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Registers new users with unique usernames and issues single-display
 * {@code flw_<userId>.<secret>} API keys.
 */
@Service
public class UserService {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{3,20}$");

    public record RegisteredUser(String userId, String username, String apiKey) {
    }

    private final UserRepository userRepository;
    private final ApiKeyService apiKeyService;
    private final SqliteTransactionExecutor txExecutor;

    public UserService(
            UserRepository userRepository,
            ApiKeyService apiKeyService,
            SqliteTransactionExecutor txExecutor) {
        this.userRepository = userRepository;
        this.apiKeyService = apiKeyService;
        this.txExecutor = txExecutor;
    }

    public RegisteredUser registerUser(String username) {
        validateUsername(username);
        String userId = UUID.randomUUID().toString();
        ApiKeyService.GeneratedKey key = apiKeyService.generateForUser(userId);
        UserRecord record = new UserRecord(userId, username, key.hash(), key.salt());

        txExecutor.executeWrite(conn -> {
            if (userRepository.existsByUsernameIgnoreCase(conn, username)) {
                throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_USERNAME",
                        "Username is already taken.");
            }
            userRepository.insert(conn, record);
            return null;
        });

        return new RegisteredUser(userId, username, key.rawApiKey());
    }

    public String resolveUserIdByUsername(String username) {
        validateUsername(username);
        UserRecord user = txExecutor.executeRead(
                conn -> userRepository.findByUsernameIgnoreCase(conn, username));
        if (user == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND",
                    "User not found.");
        }
        return user.userId();
    }

    public static void validateUsername(String username) {
        if (username == null || !USERNAME_PATTERN.matcher(username).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "Username must be 3 to 20 characters (letters, numbers, and underscores only).");
        }
    }
}
