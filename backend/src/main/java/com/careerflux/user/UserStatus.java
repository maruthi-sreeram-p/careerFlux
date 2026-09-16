package com.careerflux.user;

public enum UserStatus {
    ACTIVE,
    DISABLED,
    /**
     * The account was erased at its owner's or their college's request. The row
     * remains, stripped of identity, only so placement history that refers to it
     * stays intact. It can never sign in again.
     */
    ERASED
}
