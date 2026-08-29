/**
 * The platform operator's home.
 *
 * <p>Operations already exists and already answers this role's question — is
 * CareerFlux itself running correctly? Rather than build a second screen over
 * the same endpoint, the platform administrator's dashboard *is* that screen.
 * Re-exporting keeps one implementation, so ingestion, the event pipeline, AI
 * status and corpus figures cannot drift between two copies.
 */
export { default } from '../admin/AdminOps';
