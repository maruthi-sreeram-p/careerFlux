package com.careerflux.source.discovery;

import java.util.List;

/**
 * Company domains to point discovery at for the Indian graduate market.
 *
 * <p>This is a list of <em>where to look</em>, not a list of sources. None of
 * these domains is a job source; discovery probes each one, finds whether the
 * company publishes a readable board, and registers only what it actually finds.
 * A company can drop off this list by changing their careers stack and nothing
 * breaks — the run simply reports no board.
 *
 * <p>The selection is deliberately biased toward employers that hire engineering
 * graduates in India: product companies with Bengaluru, Hyderabad, Pune and
 * Chennai offices, Indian startups that recruit on campus, and the India arms of
 * global companies. It is a starting point rather than the market: the point of
 * discovery is that the corpus grows past this list, and the platform should end
 * up finding employers nobody typed in.
 *
 * <p>Deliberately absent: Naukri, LinkedIn, Indeed, Internshala and the other
 * aggregators. Their terms prohibit automated access, so probing them would only
 * produce sources the policy engine must then refuse. Nothing is gained by
 * discovering something we have already decided we may not read.
 *
 * <p>A domain earns its place by answering, not by being well known. Every entry
 * added on 2026-10-06 was checked against the board APIs the adapters actually
 * read, and kept only when the board returned postings located in India. That
 * second condition matters more than it sounds: a token guessed from a domain
 * lands on a company of the same name often enough that five candidates were
 * dropped for it, each with a live board and not one Indian role on it.
 *
 * <p>Companies on Darwinbox, Keka and Zoho Recruit are not listed. They are
 * common among Indian employers and discovery recognises none of them, so
 * seeding them would describe a gap rather than fill one.
 */
public final class IndianEmployerSeeds {

    private IndianEmployerSeeds() {
    }

    /** Indian product companies and startups that recruit engineering graduates. */
    public static final List<String> INDIAN_PRODUCT_COMPANIES = List.of(
            "razorpay.com",
            "zoho.com",
            "freshworks.com",
            "postman.com",
            "zomato.com",
            "swiggy.com",
            "phonepe.com",
            "meesho.com",
            "cred.club",
            "groww.in",
            "zerodha.com",
            "browserstack.com",
            "hasura.io",
            "chargebee.com",
            "darwinbox.com",
            "innovaccer.com",
            "sprinklr.com",
            "dream11.com",
            "upstox.com",
            "urbancompany.com",
            "lenskart.com",
            "nykaa.com",
            "policybazaar.com",
            "unacademy.com",
            "physicswallah.com",
            "sarvam.ai",
            "krutrim.com",
            "zeta.tech",
            "juspay.in",
            "setu.co",
            // Verified 2026-10-06: each publishes a board the adapters can read.
            "apna.co",
            "atlan.com",
            "bureau.id",
            "cars24.com",
            "cloudsek.com",
            "entropik.io",
            "epifi.com",
            "fampay.in",
            "glance.com",
            "inmobi.com",
            "interviewbit.com",
            "kredx.com",
            "kula.ai",
            "lendingkart.com",
            "mindtickle.com",
            "newtonschool.co",
            "nobroker.in",
            "refyne.co",
            "shipsy.io",
            "signoz.io",
            "spotdraft.com",
            "turtlemint.com",
            "whatfix.com");

    /** Global companies with substantial engineering presence in India. */
    public static final List<String> GLOBAL_WITH_INDIA_OFFICES = List.of(
            "atlassian.com",
            "gitlab.com",
            "stripe.com",
            "databricks.com",
            "confluent.io",
            "hashicorp.com",
            "mongodb.com",
            "elastic.co",
            "twilio.com",
            "cloudflare.com",
            "datadoghq.com",
            "snowflake.com",
            "uber.com",
            "airbnb.com",
            "wise.com",
            "canva.com",
            "grammarly.com",
            "miro.com",
            "thoughtworks.com",
            "arista.com",
            // Verified 2026-10-06: boards readable, and hiring in India.
            "druva.com",
            "hackerrank.com",
            "netradyne.com",
            "observe.ai",
            "tekion.com",
            "truecaller.com",
            "zenoti.com");

    /** Everything discovery should examine on a full run. */
    public static List<String> all() {
        List<String> domains = new java.util.ArrayList<>(INDIAN_PRODUCT_COMPANIES);
        domains.addAll(GLOBAL_WITH_INDIA_OFFICES);
        return List.copyOf(domains);
    }
}
