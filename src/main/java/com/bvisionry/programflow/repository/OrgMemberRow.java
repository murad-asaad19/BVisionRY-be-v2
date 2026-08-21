package com.bvisionry.programflow.repository;

import java.util.UUID;

/**
 * Native-query projection of an active org member (role MEMBER), read
 * straight from {@code users}. Keeps the programflow slice free of any Java
 * dependency on the {@code auth} feature.
 */
public interface OrgMemberRow {

    UUID getId();

    String getName();

    String getEmail();
}
