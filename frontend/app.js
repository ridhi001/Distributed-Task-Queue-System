// JobCraft Orchestrator Frontend Client
const API_BASE_URL = 'http://localhost:8080';
let stompClient = null;
let jobsList = [];
let selectedFilter = 'ALL';

// DOM Elements
const connectionBadge = document.getElementById('connection-badge');
const connectionText = document.getElementById('connection-text');
const jobQueueBody = document.getElementById('job-queue-body');
const standardForm = document.getElementById('standard-job-form');
const aiRouterForm = document.getElementById('ai-router-form');
const aiSubmitBtn = document.getElementById('ai-submit-btn');
const aiPromptInput = document.getElementById('ai-prompt');
const modal = document.getElementById('diagnostic-modal');

// Metrics DOM
const metricQueueDepth = document.getElementById('metric-queue-depth');
const metricSuccessRate = document.getElementById('metric-success-rate');
const metricFailures = document.getElementById('metric-failures');
const metricTotal = document.getElementById('metric-total');

// Connect to WebSockets and fetch initial data
document.addEventListener('DOMContentLoaded', () => {
    connectWebSocket();
    fetchJobs();
    fetchStats();
    setupEventListeners();
});

// Setup Form listeners and Filters
function setupEventListeners() {
    // Standard Job Submission
    standardForm.addEventListener('submit', (e) => {
        e.preventDefault();
        const type = document.getElementById('job-type').value;
        const priority = document.querySelector('input[name="priority"]:checked').value;
        let payload = document.getElementById('payload').value;
        const forceFailure = document.getElementById('force-failure').checked;

        if (forceFailure) {
            payload = JSON.stringify({ forceFailure: true });
        } else {
            try {
                // Validate JSON payload
                JSON.parse(payload);
            } catch (err) {
                alert('Invalid JSON in payload. Please enter valid JSON.');
                return;
            }
        }

        submitStandardJob(type, priority, payload);
    });

    // AI Job Router Submission
    aiRouterForm.addEventListener('submit', (e) => {
        e.preventDefault();
        const promptText = aiPromptInput.value.trim();
        if (!promptText) return;

        submitAiRouterJob(promptText);
    });

    // Queue Filters
    document.querySelectorAll('.filter-btn').forEach(btn => {
        btn.addEventListener('click', (e) => {
            document.querySelectorAll('.filter-btn').forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
            selectedFilter = btn.dataset.filter;
            renderJobsTable();
        });
    });
}

// WebSocket Connection
function connectWebSocket() {
    const socket = new SockJS(`${API_BASE_URL}/ws-jobs`);
    stompClient = Stomp.over(socket);
    // Disable debug logs to console to keep cleaner logs
    stompClient.debug = null;

    stompClient.connect({}, () => {
        // Connected
        connectionBadge.className = 'badge badge-connected';
        connectionText.textContent = 'CONNECTED';
        
        stompClient.subscribe('/topic/jobs', (message) => {
            const data = JSON.parse(message.body);
            handleWebSocketEvent(data);
        });
    }, (error) => {
        // Disconnected / Connection Error
        connectionBadge.className = 'badge badge-disconnected';
        connectionText.textContent = 'RECONNECTING...';
        console.warn('WebSocket connection lost, attempting reconnect in 3s...', error);
        setTimeout(connectWebSocket, 3000);
    });
}

// Handle real-time updates from WebSockets
function handleWebSocketEvent(data) {
    if (data.action === 'UPDATE' && data.job) {
        const index = jobsList.findIndex(j => j.id === data.job.id);
        if (index !== -1) {
            jobsList[index] = data.job;
        } else {
            jobsList.unshift(data.job); // Add new job to the top
        }
        
        // If modal is open for this job, update its diagnostics live
        if (modal.style.display === 'flex' && document.getElementById('diag-id').textContent === data.job.id) {
            updateModalContent(data.job);
        }

        renderJobsTable();
        if (data.stats) {
            updateMetrics(data.stats);
        }
    } else if (data.action === 'DELETE' && data.id) {
        jobsList = jobsList.filter(j => j.id !== data.id);
        renderJobsTable();
        fetchStats(); // Refetch stats after a deletion
    }
}

// REST API calls
async function fetchJobs() {
    try {
        const response = await fetch(`${API_BASE_URL}/api/jobs`);
        if (response.ok) {
            jobsList = await response.json();
            // Sort by created time descending initially
            jobsList.sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt));
            renderJobsTable();
        }
    } catch (err) {
        console.error('Error fetching jobs: ', err);
    }
}

async function fetchStats() {
    try {
        const response = await fetch(`${API_BASE_URL}/api/jobs/stats`);
        if (response.ok) {
            const stats = await response.json();
            updateMetrics(stats);
        }
    } catch (err) {
        console.error('Error fetching stats: ', err);
    }
}

async function submitStandardJob(type, priority, payload) {
    try {
        const response = await fetch(`${API_BASE_URL}/api/jobs`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ type, priority, payload, maxRetries: 3 })
        });
        if (response.ok) {
            // Success: Form resets, list updates via WS broadcast
            document.getElementById('force-failure').checked = false;
            document.getElementById('payload').value = '{"recipient": "user@example.com", "template": "welcome"}';
        } else {
            alert('Failed to submit job.');
        }
    } catch (err) {
        console.error('Error submitting standard job: ', err);
    }
}

async function submitAiRouterJob(prompt) {
    // UI Loading state
    aiSubmitBtn.disabled = true;
    aiSubmitBtn.querySelector('.btn-content').classList.add('hidden');
    aiSubmitBtn.querySelector('.btn-spinner').classList.remove('hidden');

    try {
        const response = await fetch(`${API_BASE_URL}/api/jobs/ai-route`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ prompt })
        });
        if (response.ok) {
            aiPromptInput.value = '';
        } else {
            alert('AI Router was unable to route this job. Please try a different query.');
        }
    } catch (err) {
        console.error('Error in AI routing: ', err);
        alert('Failed to contact AI router.');
    } finally {
        // Reset loading state
        aiSubmitBtn.disabled = false;
        aiSubmitBtn.querySelector('.btn-content').classList.remove('hidden');
        aiSubmitBtn.querySelector('.btn-spinner').classList.add('hidden');
    }
}

async function deleteJob(id) {
    if (!confirm('Are you sure you want to delete this job?')) return;
    try {
        await fetch(`${API_BASE_URL}/api/jobs/${id}`, {
            method: 'DELETE'
        });
    } catch (err) {
        console.error('Error deleting job: ', err);
    }
}

async function triggerRetry(id) {
    try {
        const response = await fetch(`${API_BASE_URL}/api/jobs/${id}/retry`, {
            method: 'POST'
        });
        if (response.ok) {
            closeModal();
        } else {
            alert('Could not retry this job.');
        }
    } catch (err) {
        console.error('Error triggering retry: ', err);
    }
}

// UI Helpers
function updateMetrics(stats) {
    metricQueueDepth.textContent = stats.queueDepth;
    metricSuccessRate.textContent = `${stats.successRate}%`;
    metricFailures.textContent = stats.failureCount;
    metricTotal.textContent = stats.totalCount;
}

function fillPrompt(text) {
    aiPromptInput.value = text;
}

function formatDate(isoStr) {
    if (!isoStr) return '-';
    const date = new Date(isoStr);
    return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

function renderJobsTable() {
    jobQueueBody.innerHTML = '';
    
    // Filter jobs list
    const filteredJobs = jobsList.filter(job => {
        if (selectedFilter === 'ALL') return true;
        if (selectedFilter === 'DLQ') return job.status === 'DLQ';
        return job.status === selectedFilter;
    });

    if (filteredJobs.length === 0) {
        jobQueueBody.innerHTML = `
            <tr class="empty-row">
                <td colspan="8"><i class="fa-regular fa-folder-open"></i> No jobs match the filter "${selectedFilter}"</td>
            </tr>
        `;
        return;
    }

    filteredJobs.forEach(job => {
        const tr = document.createElement('tr');
        tr.id = `job-row-${job.id}`;

        // Shorten ID
        const shortId = job.id.substring(0, 8);

        // Job type icons
        let typeIcon = 'fa-square-poll-vertical';
        let typeClass = 'text-green';
        if (job.type === 'SEND_EMAIL') {
            typeIcon = 'fa-envelope';
            typeClass = 'text-blue';
        } else if (job.type === 'RESIZE_IMAGE') {
            typeIcon = 'fa-image';
            typeClass = 'text-purple';
        }

        // Priority badging
        const prioLower = job.priority.toLowerCase();

        // Status pill assembly
        let statusBadgeClass = `status-${job.status.toLowerCase()}`;
        let statusBadgeText = job.status;
        if (job.status === 'RUNNING') {
            statusBadgeText = `<i class="fa-solid fa-spinner fa-spin"></i> RUNNING`;
        } else if (job.status === 'RETRYING') {
            statusBadgeText = `<i class="fa-solid fa-arrows-rotate fa-spin"></i> RETRYING`;
        } else if (job.status === 'DLQ') {
            statusBadgeText = `<i class="fa-solid fa-circle-exclamation"></i> DLQ (CLICK)`;
        }

        const isDlq = job.status === 'DLQ';

        tr.innerHTML = `
            <td title="${job.id}" class="code-snippet">${shortId}</td>
            <td><i class="fa-solid ${typeIcon} ${typeClass}"></i> ${job.type}</td>
            <td><span class="prio-badge prio-${prioLower}">${job.priority}</span></td>
            <td>
                <span class="status-pill ${statusBadgeClass}" ${isDlq ? `onclick="openDiagnostics('${job.id}')"` : ''}>
                    ${statusBadgeText}
                </span>
            </td>
            <td>${job.retryCount} / ${job.maxRetries}</td>
            <td>${formatDate(job.createdAt)}</td>
            <td>${formatDate(job.completedAt)}</td>
            <td>
                <div class="action-btns">
                    ${isDlq ? `<button class="action-btn retry-btn" onclick="openDiagnostics('${job.id}')" title="Inspect / Retry"><i class="fa-solid fa-circle-info"></i></button>` : ''}
                    <button class="action-btn delete-btn" onclick="deleteJob('${job.id}')" title="Delete job"><i class="fa-solid fa-trash-can"></i></button>
                </div>
            </td>
        `;

        jobQueueBody.appendChild(tr);
    });
}

// Modal logic
function openDiagnostics(id) {
    const job = jobsList.find(j => j.id === id);
    if (!job) return;

    updateModalContent(job);
    modal.style.display = 'flex';
}

function updateModalContent(job) {
    document.getElementById('diag-id').textContent = job.id;
    document.getElementById('diag-type').textContent = job.type;
    
    // Format JSON payload for readable display
    try {
        const parsed = JSON.parse(job.payload);
        document.getElementById('diag-payload').textContent = JSON.stringify(parsed, null, 2);
    } catch (e) {
        document.getElementById('diag-payload').textContent = job.payload;
    }

    document.getElementById('diag-error').textContent = job.errorMessage || 'No error message recorded.';

    const suggestionBox = document.getElementById('diag-ai-suggestion');
    if (job.aiAnalysis) {
        suggestionBox.innerHTML = `
            <p>${job.aiAnalysis}</p>
        `;
    } else {
        suggestionBox.innerHTML = `
            <i class="fa-solid fa-circle-notch fa-spin"></i> Consulting AI failure models... (updates automatically when ready)
        `;
    }

    // Attach retry action to button
    const retryBtn = document.getElementById('modal-retry-btn');
    retryBtn.onclick = () => triggerRetry(job.id);
}

function closeModal() {
    modal.style.display = 'none';
}

// Close modal when clicking outside contents
window.onclick = function(event) {
    if (event.target === modal) {
        closeModal();
    }
}
