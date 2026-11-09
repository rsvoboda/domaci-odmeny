package cz.domaci.odmeny.member;

import cz.domaci.odmeny.config.AppConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class MemberService {

    private final MemberRepository repository;
    private final AppConfig config;

    @Inject
    public MemberService(MemberRepository repository, AppConfig config) {
        this.repository = repository;
        this.config = config;
    }

    public List<Member> listHouseholdMembers() {
        return repository.listByHousehold(config.householdId());
    }

    public Optional<Member> findById(String memberId) {
        return repository.findMember(config.householdId(), memberId);
    }

    public Optional<Member> findByCognitoSub(String cognitoSub) {
        return repository.findByCognitoSub(config.householdId(), cognitoSub);
    }
}
