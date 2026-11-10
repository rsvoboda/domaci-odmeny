package cz.domaci.odmeny.member;

import cz.domaci.odmeny.audit.AuditEvent;

import java.util.List;
import java.util.Optional;

/**
 * Storage-agnostic access to household members. The domain must not depend on
 * DynamoDB APIs.
 */
public interface MemberRepository {

    List<Member> listByHousehold(String householdId);

    Optional<Member> findMember(String householdId, String memberId);

    Optional<Member> findByCognitoSub(String householdId, String cognitoSub);

    /**
     * Creates or replaces a member. Used by the seeders.
     */
    void put(Member member);

    /**
     * Updates an existing member and writes the audit event in the same
     * transaction.
     *
     * @throws IllegalStateException when the member no longer exists
     */
    void update(Member member, AuditEvent audit);
}
