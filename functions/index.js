
const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { initializeApp } = require("firebase-admin/app");
const { getDatabase } = require("firebase-admin/database");
const crypto = require("crypto");

initializeApp({
  databaseURL: "https://detectcameracentral-default-rtdb.firebaseio.com"
});

const db = getDatabase();
const REGION = "us-central1";
const TICKET_LIFETIME_MS = 2 * 60 * 1000;

async function requireAdmin(request) {
  if (!request.auth || !request.auth.uid) {
    throw new HttpsError("unauthenticated", "Debes iniciar sesión.");
  }

  const uid = request.auth.uid;
  const snapshot = await db.ref("admins/" + uid).get();

  if (snapshot.val() !== true) {
    throw new HttpsError(
      "permission-denied",
      "No tienes permisos de administrador."
    );
  }

  return uid;
}

function validateDeviceId(deviceId) {
  if (
    typeof deviceId !== "string" ||
    !/^[a-fA-F0-9]{8,128}$/.test(deviceId)
  ) {
    throw new HttpsError(
      "invalid-argument",
      "Identificador de dispositivo inválido."
    );
  }
}

async function getRegisteredDevice(deviceId, requireTunnel) {
  const snapshot = await db.ref("dispositivos/" + deviceId).get();

  if (!snapshot.exists()) {
    throw new HttpsError(
      "not-found",
      "El dispositivo no está registrado."
    );
  }

  const device = snapshot.val();

  if (
    !device ||
    device.androidId !== deviceId ||
    typeof device.firebaseUid !== "string" ||
    !device.firebaseUid
  ) {
    throw new HttpsError(
      "failed-precondition",
      "El registro del dispositivo no es válido."
    );
  }

  if (requireTunnel) {
    if (
      typeof device.url !== "string" ||
      !/^https:\/\/[a-z0-9-]+\.trycloudflare\.com\/?$/i.test(device.url)
    ) {
      throw new HttpsError(
        "failed-precondition",
        "El túnel del dispositivo no está disponible."
      );
    }
  }

  return device;
}

exports.createDeviceAccessTicket = onCall(
  {
    region: REGION,
    maxInstances: 10
  },
  async (request) => {
    await requireAdmin(request);

    const deviceId = request.data && request.data.deviceId;
    validateDeviceId(deviceId);

    const device = await getRegisteredDevice(deviceId, true);

    const ticket = crypto.randomBytes(32).toString("base64url");

    const ticketHash = crypto
      .createHash("sha256")
      .update(ticket)
      .digest("hex");

    const now = Date.now();
    const expiresAt = now + TICKET_LIFETIME_MS;

    const authRef = db.ref("deviceAuth/" + deviceId);

    const transaction = await authRef.transaction((current) => {
      const state = current && typeof current === "object"
        ? current
        : {};

      const generation = Number(state.generation || 0);

      state.generation = generation;

      state.ticket = {
        hash: ticketHash,
        createdAt: now,
        expiresAt: expiresAt,
        generation: generation
      };

      return state;
    });

    if (!transaction.committed) {
      throw new HttpsError(
        "aborted",
        "No se pudo reservar el ticket de acceso."
      );
    }

    return {
      ticket: ticket,
      tunnelUrl: device.url.replace(/\/+$/, ""),
      expiresAt: expiresAt
    };
  }
);

exports.revokeDeviceAccess = onCall(
  {
    region: REGION,
    maxInstances: 10
  },
  async (request) => {
    await requireAdmin(request);

    const deviceId = request.data && request.data.deviceId;
    validateDeviceId(deviceId);

    // Se puede revocar aunque el teléfono esté desconectado.
    await getRegisteredDevice(deviceId, false);

    const authRef = db.ref("deviceAuth/" + deviceId);

    const transaction = await authRef.transaction((current) => {
      const state = current && typeof current === "object"
        ? current
        : {};

      const generation = Number(state.generation || 0);

      state.generation = generation + 1;

      delete state.ticket;

      return state;
    });

    if (!transaction.committed) {
      throw new HttpsError(
        "aborted",
        "No se pudo revocar el acceso del dispositivo."
      );
    }

    return {
      success: true,
      generation: Number(
        transaction.snapshot.child("generation").val() || 0
      )
    };
  }
);
