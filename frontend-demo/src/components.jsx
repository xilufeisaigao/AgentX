import React from "react";
import { PHASE_STEPS, statusTone } from "./controlPlane";
import { useI18n } from "./i18n";

export function Panel({ title, kicker, children, wide = false, dark = false }) {
  return (
    <section className={`panel ${wide ? "panel--wide" : ""} ${dark ? "panel--dark" : ""}`}>
      <div className="panel-head">
        <div>
          {kicker ? <p className="eyebrow">{kicker}</p> : null}
          <h3>{title}</h3>
        </div>
      </div>
      <div className="panel-body">{children}</div>
    </section>
  );
}

export function Metric({ label, value, inverse = false }) {
  return (
    <div className={`metric ${inverse ? "metric--inverse" : ""}`}>
      <span>{label}</span>
      <strong>{String(value)}</strong>
    </div>
  );
}

export function PropertyRow({ label, value }) {
  return (
    <div className="property-row">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

export function Field({ label, children }) {
  return (
    <label className="field">
      <span>{label}</span>
      {children}
    </label>
  );
}

export function StatusBadge({ value, soft = false }) {
  const { label } = useI18n();
  return (
    <span className={`status-badge tone-${statusTone(value)} ${soft ? "is-soft" : ""}`}>
      {label(value)}
    </span>
  );
}

export function ActionButton({ tone = "ghost", small = false, disabled = false, onClick, children }) {
  return (
    <button
      type="button"
      className={`action-button action-button--${tone} ${small ? "action-button--small" : ""}`}
      disabled={disabled}
      onClick={onClick}
    >
      {children}
    </button>
  );
}

export function EmptyCard({ title, body, children, large = false }) {
  return (
    <div className={`empty-card ${large ? "empty-card--large" : ""}`}>
      <strong>{title}</strong>
      <p>{body}</p>
      {children ? <div className="button-row">{children}</div> : null}
    </div>
  );
}

export function PhaseRibbon({ current }) {
  const { label } = useI18n();
  const currentIndex = Math.max(PHASE_STEPS.indexOf(current), 0);
  return (
    <div className="phase-ribbon">
      {PHASE_STEPS.map((step, index) => (
        <div
          key={step}
          className={`phase-chip ${step === current ? "is-current" : ""} ${index < currentIndex ? "is-complete" : ""}`}
        >
          {label(step)}
        </div>
      ))}
    </div>
  );
}

export function Toast({ toast }) {
  if (!toast) {
    return null;
  }
  return <div className={`toast tone-${toast.tone}`}>{toast.message}</div>;
}

export function CreateSessionDialog({ pending, title, onTitleChange, onClose, onCreate }) {
  const { t } = useI18n();
  return (
    <div className="dialog-backdrop" role="presentation" onClick={onClose}>
      <div className="dialog panel" role="dialog" aria-modal="true" onClick={(event) => event.stopPropagation()}>
        <p className="eyebrow">{t("createSession")}</p>
        <h3>{t("createMission")}</h3>
        <p className="panel-copy">{t("createMissionLead")}</p>
        <Field label={t("sessionTitle")}>
          <input value={title} onChange={(event) => onTitleChange(event.target.value)} placeholder={t("sessionTitlePlaceholder")} />
        </Field>
        <div className="button-row">
          <ActionButton tone="ghost" onClick={onClose}>{t("cancel")}</ActionButton>
          <ActionButton tone="primary" onClick={onCreate} disabled={pending}>
            {pending ? t("creating") : t("createSessionButton")}
          </ActionButton>
        </div>
      </div>
    </div>
  );
}
