# T33 — Check-in de resultado percibido por el padre

## Resumen

Definición del contrato y evento de micro-feedback para que el padre reporte el resultado percibido ("¿esto está ayudando?").

## Contrato

### Tabla: `parent_outcome_checkins`

| Campo | Tipo | Descripción |
|-------|------|-------------|
| `id` | UUID | PK |
| `parent_id` | UUID | FK → auth.users |
| `device_id` | UUID | FK → devices (nullable) |
| `rating` | enum | POSITIVE / NEUTRAL / NEGATIVE |
| `comment` | TEXT | Opcional, max 500 chars |
| `period_start` | DATE | Inicio del período (quincenal) |
| `period_end` | DATE | Fin del período |
| `created_at` | TIMESTAMPTZ | Timestamp de creación |
| `updated_at` | TIMESTAMPTZ | Timestamp de actualización |

### Restricciones

- Unique constraint: `(parent_id, period_start, period_end)`
- Un check-in por padre por período (quincenal)

### RLS

```sql
-- El padre solo ve sus propios check-ins
CREATE POLICY parent_own_checkins ON parent_outcome_checkins
    FOR ALL
    USING (parent_id = auth.uid());
```

## Cadencia

- **Quincenal** (cada 15 días)
- No obligatorio, descartable
- No bloquea ninguna funcionalidad

## Payload del Evento

```json
{
  "event_type": "parent_outcome_checkin",
  "event_version": 1,
  "device_id": "uuid-del-dispositivo",
  "parent_id": "uuid-del-padre",
  "client_ts": "2024-01-15T12:00:00Z",
  "props": {
    "rating": "POSITIVE",
    "has_comment": "true",
    "period_start": "2024-01-01",
    "period_end": "2024-01-15"
  }
}
```

## UI (App del Padre - Fuera de alcance)

La UI para capturar el check-in vive en la app del padre (fuera de alcance de este proyecto). El contrato define:

```
┌─────────────────────────────────────────────────────────────────────┐
│  PREGUNTA DE CHECK-IN                                              │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ¿Cómo te ha ido con el control parental esta quincena?             │
│                                                                     │
│  😊 Positivo   😐 Neutral   ☹️ Negativo                            │
│                                                                     │
│  [Comentario opcional________________________]                       │
│                                                                     │
│  [Enviar]                                                          │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

## Edge Functions Relacionadas

### `insert-parent-checkin`

```typescript
// supabase/functions/insert-parent-checkin/index.ts
export async function insertParentCheckin(
  rating: 'POSITIVE' | 'NEUTRAL' | 'NEGATIVE',
  comment?: string,
  periodStart?: string,
  periodEnd?: string,
  deviceId?: string
)
```

### `get-parent-checkins`

```typescript
// supabase/functions/get-parent-checkins/index.ts
export async function getParentCheckins(
  parentId: string,
  limit?: number
): Promise<ParentOutcomeCheckin[]>
```

## Análisis

El panel del padre puede usar los check-ins para:

1. **Tendencia**: ¿Mejora o empeora la percepción?
2. **Correlación**: ¿Se correlaciona con eventos de behavioral_events?
3. **Segmentación**: ¿Padres nuevos vs antiguos reportan diferente?

## §0.9 Compliance

- Datos del padre (no del menor)
- Comment es opcional y moderado
- Sin contenido del menor en el payload
- Descartable y no bloqueante
