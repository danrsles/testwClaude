package com.example.demo.services;

import java.time.Instant;

/**
 * Who is making the request, as the verified Clerk session token says.
 *
 * Everything but clerkId comes from custom claims, set in the Clerk dashboard
 * under Sessions > Customize session token, and is null when the claim is
 * missing or the Clerk user has no such value:
 *
 * <pre>
 * { "username": "{{user.username}}",
 *   "email": "{{user.primary_email_address}}",
 *   "lastLogin": "{{user.last_sign_in_at}}",
 *   "imageUrl": "{{user.image_url}}" }
 * </pre>
 *
 * @param clerkId      the token's "sub": the Clerk user id, never null
 * @param username     "username"
 * @param email        "email", the user's primary email address in Clerk
 * @param lastSignInAt "lastLogin", when the user last signed in
 * @param imageUrl     "imageUrl", the profile picture: the user's photo, or
 *                     Clerk's generated silhouette when they have none
 */
public record ClerkIdentity(String clerkId, String username, String email, Instant lastSignInAt,
        String imageUrl) {

    /** An identity from a token without the picture claim. */
    public ClerkIdentity(String clerkId, String username, String email, Instant lastSignInAt) {
        this(clerkId, username, email, lastSignInAt, null);
    }

    /** An identity from a token carrying only a username, as in most tests. */
    public ClerkIdentity(String clerkId, String username) {
        this(clerkId, username, null, null);
    }
}
