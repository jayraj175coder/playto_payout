const orders = [
    { id: "O1", weight: 10, volume: 14, cost: 450, priority: "High", zone: "North" },
    { id: "O2", weight: 18, volume: 16, cost: 250, priority: "Medium", zone: "South" },
    { id: "O3", weight: 12, volume: 10, cost: 520, priority: "High", zone: "North" }
];

const orderForm = document.getElementById("orderForm");
const optimizeBtn = document.getElementById("optimizeBtn");
const ordersTableBody = document.getElementById("ordersTableBody");
const resultArea = document.getElementById("resultArea");
const statusMessage = document.getElementById("statusMessage");
const orderCount = document.getElementById("orderCount");

function renderOrders() {
    orderCount.textContent = `${orders.length} Order${orders.length === 1 ? "" : "s"}`;

    if (orders.length === 0) {
        ordersTableBody.innerHTML = `
            <tr class="empty-row">
                <td colspan="6">No orders added yet.</td>
            </tr>
        `;
        return;
    }

    ordersTableBody.innerHTML = orders.map((order) => `
        <tr>
            <td>${escapeHtml(order.id)}</td>
            <td>${order.weight}</td>
            <td>${order.volume}</td>
            <td>${order.cost}</td>
            <td><span class="priority-pill priority-${order.priority.toLowerCase()}">${order.priority}</span></td>
            <td>${escapeHtml(order.zone)}</td>
        </tr>
    `).join("");
}

function renderResult(data) {
    if (!data.containers || data.containers.length === 0) {
        resultArea.innerHTML = `
            <div class="empty-state">
                <h3>No Containers Generated</h3>
                <p>Add valid orders and try again.</p>
            </div>
        `;
        return;
    }

    const averageWeight = (
        data.containers.reduce((sum, container) => sum + Number(container.weightUtilization), 0) /
        data.containers.length
    ).toFixed(2);
    const averageVolume = (
        data.containers.reduce((sum, container) => sum + Number(container.volumeUtilization), 0) /
        data.containers.length
    ).toFixed(2);

    resultArea.innerHTML = `
        <div class="summary-strip">
            <div class="summary-chip">
                <span>Total Containers</span>
                <strong>${data.containerCount}</strong>
            </div>
            <div class="summary-chip">
                <span>Avg Utilization</span>
                <strong>${averageWeight}% / ${averageVolume}%</strong>
            </div>
        </div>
        ${data.containers.map((container, index) => `
            <article class="container-card" style="animation-delay:${index * 0.08}s;">
                <div class="container-header">
                    <h3>${container.name}</h3>
                    <span class="panel-tag">${container.orders.length} Orders</span>
                </div>
                <p class="container-orders">${container.orders.map(order => escapeHtml(order.id)).join(", ")}</p>
                <div class="metric">
                    <div class="metric-line">
                        <span>Weight Utilization</span>
                        <span>${Number(container.weightUtilization).toFixed(2)}% (${container.totalWeight}/50)</span>
                    </div>
                    <div class="bar"><span style="width:${Math.min(Number(container.weightUtilization), 100)}%"></span></div>
                </div>
                <div class="metric">
                    <div class="metric-line">
                        <span>Volume Utilization</span>
                        <span>${Number(container.volumeUtilization).toFixed(2)}% (${container.totalVolume}/50)</span>
                    </div>
                    <div class="bar"><span style="width:${Math.min(Number(container.volumeUtilization), 100)}%"></span></div>
                </div>
            </article>
        `).join("")}
    `;
}

orderForm.addEventListener("submit", (event) => {
    event.preventDefault();

    const newOrder = {
        id: document.getElementById("orderId").value.trim(),
        weight: Number(document.getElementById("weight").value),
        volume: Number(document.getElementById("volume").value),
        cost: Number(document.getElementById("cost").value),
        priority: document.getElementById("priority").value,
        zone: document.getElementById("zone").value.trim()
    };

    if (!newOrder.id || !newOrder.zone || newOrder.weight <= 0 || newOrder.volume <= 0 || newOrder.weight > 50 || newOrder.volume > 50) {
        statusMessage.textContent = "Each order must have an ID, zone, and weight/volume values between 1 and 50.";
        return;
    }

    orders.push(newOrder);
    renderOrders();
    orderForm.reset();
    document.getElementById("priority").value = "High";
    statusMessage.textContent = `Order ${newOrder.id} added to the queue.`;
});

optimizeBtn.addEventListener("click", async () => {
    if (orders.length === 0) {
        statusMessage.textContent = "Add at least one order before optimizing.";
        return;
    }

    statusMessage.textContent = "Optimizing containers using greedy and bin-packing logic...";

    try {
        const response = await fetch("/optimize", {
            method: "POST",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify({ orders })
        });

        const data = await response.json();
        if (!response.ok) {
            throw new Error(data.error || "Optimization failed.");
        }

        renderResult(data);
        statusMessage.textContent = `Optimization complete. ${data.containerCount} container(s) generated.`;
    } catch (error) {
        statusMessage.textContent = error.message || "Unable to reach the optimization server.";
    }
});

function escapeHtml(value) {
    return String(value)
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#39;");
}

renderOrders();
