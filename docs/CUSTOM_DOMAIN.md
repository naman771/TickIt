# Student domain preparation — 5 November 2026

Owner preference: **tickit**, with **tick-it** as the only requested fallback. No domain has been registered by this work, and no DNS, hosting or identity settings have been changed.

## Registry checks

Authoritative RDAP queries used the [IANA bootstrap](https://data.iana.org/rdap/dns.json). `.me` used the IANA-listed `whois.nic.me` service because it has no RDAP bootstrap entry.

| Domain | Registry observation |
| --- | --- |
| tickit.dev, tickit.app, tickit.live | Registered |
| tick-it.dev, tick-it.app | Registered |
| tickit.me, tick-it.me | Registered |
| tickit.tech, tickit.studio, tickit.software | No registry record found |
| tick-it.live, tick-it.tech, tick-it.studio, tick-it.software | No registry record found |

A missing registry record does not establish availability, standard pricing, student eligibility or ownership. The preliminary candidate is **tickit.tech**, preserving the requested brand. Name.com and .TECH public browser searches presented security-verification pages; no challenge was bypassed, cart created or checkout completed.

## Claim in the owner's account

Use the [.TECH GitHub student offer](https://get.tech/github-student-developer-pack) to search `tickit.tech` and verify the GitHub Education account at checkout. The offer sponsors a domain for one year. Name.com's separate [student offer](https://www.name.com/partner/github-students) is another route for eligible extensions; its [official instructions](https://www.name.com/support/articles/360036364834-signing-up-for-github-student-developer-pack) describe signing in through the partner flow and applying the promotion at checkout.

The owner must complete account sign-in and required registrant/contact details. Confirm the selected domain is standard/eligible, the final payable total is **zero**, no paid add-ons or paid renewal obligation is accepted, and the renewal date/price and auto-renew preference are understood. If it is premium or the promotion does not make the total zero, stop and use another requested-name candidate. There is no authorization for buying a paid alternative, transferring someone else's domain or changing the brand.

## Connect after ownership is confirmed

Start with the customer domain. Render provides TLS automatically; a separate paid certificate is unnecessary. Render's Hobby plan currently includes two custom domains and bills additional domains, and adding an apex automatically creates a `www` redirect. Verify actual workspace usage and how the generated redirect is counted before adding any organizer subdomain. Stay within the included allowance; do not create an extra paid hostname.

For the selected domain, obtain the current DNS instructions from Render's service settings. The prepared customer destination is `tickit-mpy2.onrender.com`. Name.com supports an apex ANAME/ALIAS; other providers may use Render's documented apex A record. A `www` CNAME uses that same service destination. The existing organizer destination is `tickit-organizers.onrender.com`; an organizer custom hostname is optional and contingent on confirmed free quota. Preserve unrelated DNS records; use the provider's current instructions rather than blindly replacing the zone.

After ownership, DNS and TLS work, add the exact new application origins to the existing Clerk/application allowlists while retaining working origins during acceptance. Verify sign-in, public catalog, account isolation, organizer CORS and deep links on the real origins. Update public email links and the organizer API base only after the new origin passes checks. A domain does not automatically activate a production Clerk instance; changing issuers or migrating customer identities is separate work. Razorpay remains test-only.

Official hosting references: [Render custom domains and included allowance](https://render.com/docs/custom-domains), [DNS records](https://render.com/docs/configure-other-dns).
