package cz.domaci.odmeny.member;

import java.util.List;
import java.util.Optional;

/**
 * Storage-agnostic access to household members. The domain must not depend on
 * DynamoDB APIs.
 */
public interface MemberRepository {

    List<Member> listByHousehold(String householdId);

    Optional<Member> findByCognitoSub(String householdId, String cognitoSub);

    void put(Member member);
}
