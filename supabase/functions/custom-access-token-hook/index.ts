// T14: Custom Access Token Hook
// Registra en: Authentication → Hooks → Create new hook
// Tipo: Verify JWT → Custom access token hook
// Language: Typescript

// Este hook copia app_metadata.device_id Y app_metadata.parent_id a
// claims de primer nivel del JWT. Es invocado por Supabase Auth en cada
// emisión de access token (ver Authentication → Hooks → Custom Access
// Token Hook en el dashboard de Supabase).
//
// - device_id: claim para sesiones de dispositivos emparejados. Es lo que
//   las policies RLS tipo `auth.jwt() ->> 'device_id'` usan para filtrar.
// - parent_id: claim para sesiones de padres reales. Es la clave de las
//   policies RLS tipo `parent_id = auth.uid()`. Se inyecta cuando
//   app_metadata.parent_id está presente (escrito por
//   DeviceAuthManager.verifyMagicLinkOtp tras un magic-link exitoso).

interface HookEvent {
  type: 'verify-jwt'
  event: {
    jwt: {
      sub: string
      role: string
      aud: string
      exp: number
      iat: number
      email?: string
      app_metadata: {
        provider?: string
        providers?: string[]
        device_id?: string
        parent_id?: string
        [key: string]: unknown
      }
      user_metadata?: {
        name?: string
        [key: string]: unknown
      }
      [key: string]: unknown
    }
    claims: {
      role: string
      [key: string]: unknown
    }
  }
}

export async function customAccessTokenHook(event: HookEvent): Promise<{ role?: string; device_id?: string; parent_id?: string; [key: string]: unknown }> {
  const { jwt } = event.event
  const appMeta = jwt.app_metadata ?? {}

  // Caso A: device_id presente (child emparejado o parent con sesión de
  // dispositivo). Copiamos device_id y, si también hay parent_id, lo
  // inyectamos como claim top-level. device_id es obligatorio para que
  // las policies de tipo agent (`auth.jwt() ->> 'device_id'`) matcheen.
  if (appMeta.device_id) {
    const claims: Record<string, unknown> = {
      ...event.event.claims,
      device_id: appMeta.device_id,
      iss: 'supabase',
      iat: jwt.iat,
      exp: jwt.exp,
    }
    // Solo agregar parent_id si está presente y no es una cadena vacía.
    // Las policies RLS keyed en `parent_id = auth.uid()` deben fallar
    // (no match) para child JWTs sin parent_id — no propagamos `null`.
    if (typeof appMeta.parent_id === 'string' && appMeta.parent_id.length > 0) {
      claims.parent_id = appMeta.parent_id
    }
    return claims as { role?: string; device_id?: string; parent_id?: string; [key: string]: unknown }
  }

  // Caso B: solo parent_id presente (parent sin device — sesión de parent
  // puro, sin dispositivo emparejado todavía). Inyectamos parent_id.
  if (typeof appMeta.parent_id === 'string' && appMeta.parent_id.length > 0) {
    return {
      ...event.event.claims,
      parent_id: appMeta.parent_id,
      iss: 'supabase',
      iat: jwt.iat,
      exp: jwt.exp,
    } as { role?: string; device_id?: string; parent_id?: string; [key: string]: unknown }
  }

  // Caso C: ni device_id ni parent_id presentes. Devolvemos los claims
  // originales sin tocar (preservando `iss`, `iat`, `exp` implícitos).
  return event.event.claims
}

// Ejemplo de JWT resultante después del hook:
//
// Payload:
// {
//   "role": "authenticated",
//   "device_id": "uuid-del-dispositivo",  <-- Copiado del app_metadata
//   "sub": "user-uuid",
//   "email": "device@example.com",
//   "iat": 1234567890,
//   "exp": 1234571490
// }

// Este device_id se puede acceder con:
// auth.jwt() ->> 'device_id' en RLS policies
