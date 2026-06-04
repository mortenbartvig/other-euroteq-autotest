interface Step {
  step?: string;
  status?: string;
  url?: string;
  httpStatus?: number;
  error?: string;
  [key: string]: unknown;
}

interface StepDetailsProps {
  stepDetailsJson: string;
}

function statusBadgeClass(status?: string): string {
  if (!status) return 'badge badge-secondary';
  const s = status.toLowerCase();
  if (s === 'success' || s === 'ok') return 'badge badge-success';
  if (s === 'error' || s === 'failed' || s === 'fail') return 'badge badge-danger';
  if (s === 'skipped') return 'badge badge-secondary';
  return 'badge badge-warning';
}

function StepRow({ step, index }: { step: Step; index: number }) {
  const extras = Object.entries(step).filter(
    ([k]) => !['step', 'status', 'url', 'httpStatus', 'error'].includes(k)
  );

  return (
    <div className="step-row">
      <div className="step-row-header">
        <span className="step-number">#{index + 1}</span>
        {step.step && <span className="step-name">{step.step}</span>}
        {step.status && (
          <span className={statusBadgeClass(step.status)}>{step.status}</span>
        )}
        {step.httpStatus !== undefined && (
          <span className="step-http">HTTP {step.httpStatus}</span>
        )}
      </div>
      {step.url && (
        <div className="step-detail-line">
          <span className="step-detail-label">URL:</span>
          <span className="step-detail-value step-url">{step.url}</span>
        </div>
      )}
      {step.error && (
        <div className="step-detail-line">
          <span className="step-detail-label">Error:</span>
          <span className="step-detail-value step-error">{step.error}</span>
        </div>
      )}
      {extras.map(([k, v]) => (
        <div className="step-detail-line" key={k}>
          <span className="step-detail-label">{k}:</span>
          <span className="step-detail-value">
            {typeof v === 'object' ? JSON.stringify(v, null, 2) : String(v)}
          </span>
        </div>
      ))}
    </div>
  );
}

export function StepDetails({ stepDetailsJson }: StepDetailsProps) {
  let steps: Step[];
  try {
    const parsed = JSON.parse(stepDetailsJson);
    steps = Array.isArray(parsed) ? parsed : [parsed];
  } catch {
    return (
      <pre className="step-raw">{stepDetailsJson}</pre>
    );
  }

  if (steps.length === 0) {
    return <p className="text-muted">No steps recorded.</p>;
  }

  return (
    <div className="step-details">
      {steps.map((step, i) => (
        <StepRow key={i} step={step} index={i} />
      ))}
    </div>
  );
}
