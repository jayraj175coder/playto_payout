const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || "https://playto-payout-2-8gur.onrender.com/api/v1";

async function request(path, { merchantId, method = "GET", body, idempotencyKey } = {}) {
  const headers = {
    "Content-Type": "application/json",
  };

  if (merchantId) {
    headers["X-Merchant-External-Id"] = merchantId;
  }

  if (idempotencyKey) {
    headers["Idempotency-Key"] = idempotencyKey;
  }

  const response = await fetch(`${API_BASE_URL}${path}`, {
    method,
    headers,
    body: body ? JSON.stringify(body) : undefined,
  });

  const payload = await response.json();
  if (!response.ok) {
    throw new Error(payload.detail || "Request failed");
  }
  return payload;
}

export function fetchMerchants() {
  return request("/merchants/");
}

export function fetchDashboard(merchantId) {
  return request("/dashboard/", { merchantId });
}

export function createPayout(merchantId, payload, idempotencyKey) {
  return request("/payouts/", {
    merchantId,
    method: "POST",
    body: payload,
    idempotencyKey,
  });
}

