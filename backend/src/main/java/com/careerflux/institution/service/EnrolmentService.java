package com.careerflux.institution.service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.careerflux.common.TextUtils;
import com.careerflux.common.error.BadRequestException;
import com.careerflux.common.error.InstitutionUnresolvedException;
import com.careerflux.institution.domain.Institution;
import com.careerflux.institution.domain.InstitutionStatus;
import com.careerflux.institution.repository.InstitutionRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decides which college a person registering actually belongs to.
 *
 * <p>This is the front door of a multi-tenant system, and getting it wrong means
 * strangers landing inside a college's data. There is deliberately no fallback
 * that quietly assigns an unrecognised registrant to "the first institution" or
 * "the default one": if neither the email domain nor a code identifies a college,
 * registration is refused and the person is told to ask their placement office.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>An explicit registration code, if one was supplied.</li>
 *   <li>The email domain, matched against the domains a college has claimed.</li>
 * </ol>
 */
@Service
public class EnrolmentService {

    private static final Logger log = LoggerFactory.getLogger(EnrolmentService.class);

    private final InstitutionRepository institutionRepository;

    public EnrolmentService(InstitutionRepository institutionRepository) {
        this.institutionRepository = institutionRepository;
    }

    /**
     * Resolves the institution a registrant belongs to.
     *
     * @throws BadRequestException when no college claims them, or the one that
     *         does is not accepting students yet
     */
    @Transactional(readOnly = true)
    public Institution resolveForRegistration(String email, String registrationCode) {
        Institution resolved = byCode(registrationCode)
                .or(() -> byEmailDomain(email))
                .orElseThrow(() -> {
                    log.info("Registration refused for {}: no institution claims this address",
                            maskEmail(email));
                    return new InstitutionUnresolvedException(
                            "We could not work out which institution you belong to. "
                                    + "Use your college email address, or ask your placement office "
                                    + "for a registration code.");
                });

        if (resolved.getStatus() == InstitutionStatus.SUSPENDED) {
            throw new BadRequestException(resolved.getName() + " is not currently accepting sign-ins.");
        }
        return resolved;
    }

    private Optional<Institution> byCode(String registrationCode) {
        if (!TextUtils.hasText(registrationCode)) {
            return Optional.empty();
        }
        String code = registrationCode.strip();
        // Codes are short and low-cardinality, so the whole set is scanned rather
        // than adding a lookup that would need its own uniqueness rules.
        return institutionRepository.findAll().stream()
                .filter(institution -> code.equalsIgnoreCase(institution.getRegistrationCode()))
                .findFirst();
    }

    private Optional<Institution> byEmailDomain(String email) {
        if (!TextUtils.hasText(email)) {
            return Optional.empty();
        }
        String normalized = email.strip().toLowerCase(Locale.ROOT);
        List<Institution> claiming = institutionRepository.findAll().stream()
                .filter(institution -> institution.acceptsEmail(normalized))
                .toList();

        if (claiming.size() > 1) {
            // Two colleges claiming one domain is a configuration error, and
            // guessing between them would put a student in the wrong place.
            log.error("Email domain of {} is claimed by {} institutions: {}",
                    maskEmail(normalized), claiming.size(),
                    claiming.stream().map(Institution::getSlug).toList());
            throw new InstitutionUnresolvedException(
                    "That email domain is registered to more than one institution. "
                            + "Please use a registration code.");
        }
        return claiming.stream().findFirst();
    }

    /** Keeps a full address out of the logs while leaving enough to debug with. */
    private String maskEmail(String email) {
        if (email == null) {
            return "(none)";
        }
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***" + (at < 0 ? "" : email.substring(at));
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
