import Keycloak from 'keycloak-js'

// Platform SSO client. URL/realm/client can be overridden via Vite env; defaults target the
// docker-compose Keycloak (backend/docker-compose.yml → keycloak/saas-platform-realm.json).
export const keycloak = new Keycloak({
  url: import.meta.env.VITE_KEYCLOAK_URL ?? 'http://localhost:8080',
  realm: import.meta.env.VITE_KEYCLOAK_REALM ?? 'saas-platform',
  clientId: import.meta.env.VITE_KEYCLOAK_CLIENT_ID ?? 'approval-web',
})
