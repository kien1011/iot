package vn.ptit.iot.dto;

import java.security.Principal;

/** Identity associated with an access token; never contains a password. */
public record AuthenticatedUser(Long id, String username, String fullName) implements Principal {
    @Override public String getName() { return username; }
    public UserResponse response() { return new UserResponse(id, username, fullName); }
}
