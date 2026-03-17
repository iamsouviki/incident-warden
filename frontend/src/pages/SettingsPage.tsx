import React, { useEffect, useMemo, useState } from 'react';
import { authFetch, extractApiError, SIMPLE_ERROR_MESSAGE } from '../services/api';

interface SourceConfig {
  name: string;
  type: string;
  baseUrl: string;
  authType: string;
  username: string;
  apiKey: string;
  tenant: string;
  enabled: boolean;
  extraConfig: string;
}

interface LlmConfig {
  name: string;
  providerType: string;
  protocol: string;
  apiUrl: string;
  apiKey: string;
  model: string;
  embeddingModel: string;
  enabled: boolean;
  headersJson: string;
  optionsJson: string;
}

interface EnvVariableConfig {
  id?: string;
  key: string;
  value: string;
  maskedValue?: string;
  secret: boolean;
  scope: string;
  targetEnvironment: string;
  description: string;
}

const newSource = (): SourceConfig => ({
  name: '',
  type: 'SERVICENOW',
  baseUrl: '',
  authType: 'api_key',
  username: '',
  apiKey: '',
  tenant: '',
  enabled: true,
  extraConfig: '{}',
});

const newLlm = (): LlmConfig => ({
  name: '',
  providerType: 'OPENAI',
  protocol: 'openai-compatible',
  apiUrl: '',
  apiKey: '',
  model: '',
  embeddingModel: '',
  enabled: true,
  headersJson: '{}',
  optionsJson: '{}',
});

const newEnvVar = (): EnvVariableConfig => ({
  key: '',
  value: '',
  secret: true,
  scope: 'TENANT',
  targetEnvironment: 'default',
  description: '',
});

const SettingsPage: React.FC<{ tenantId: string }> = ({ tenantId }) => {
  const [incidentSources, setIncidentSources] = useState<SourceConfig[]>([]);
  const [llmProviders, setLlmProviders] = useState<LlmConfig[]>([]);
  const [incidentDefaults, setIncidentDefaults] = useState({
    defaultSourceSystem: 'manual',
    autoProcessOnCreate: false,
  });
  const [envVariables, setEnvVariables] = useState<EnvVariableConfig[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  const sourceOptions = useMemo(
    () => ['manual', ...incidentSources.filter(source => source.enabled && source.name.trim()).map(source => source.name.trim())],
    [incidentSources]
  );

  const loadSettings = async () => {
    setLoading(true);
    try {
      const response = await authFetch(`/api/v1/settings?tenantId=${tenantId}`);
      if (!response.ok) {
        setError(await extractApiError(response));
        return;
      }
      const data = await response.json();
      setIncidentSources(Array.isArray(data.incidentSources) ? data.incidentSources.map(deserializeSource) : []);
      setLlmProviders(Array.isArray(data.llmProviders) ? data.llmProviders.map(deserializeLlm) : []);
      setIncidentDefaults({
        defaultSourceSystem: data.incidentDefaults?.defaultSourceSystem || 'manual',
        autoProcessOnCreate: Boolean(data.incidentDefaults?.autoProcessOnCreate),
      });
      setEnvVariables(Array.isArray(data.envVariables) ? data.envVariables.map(deserializeEnvVar) : []);
      setError(null);
    } catch {
      setError(SIMPLE_ERROR_MESSAGE);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { loadSettings(); }, [tenantId]);

  const saveSettings = async () => {
    setSaving(true);
    setError(null);
    setMessage(null);
    try {
      const response = await authFetch(`/api/v1/settings?tenantId=${tenantId}`, {
        method: 'PUT',
        body: JSON.stringify({
          incidentSources: incidentSources.map(serializeSource),
          llmProviders: llmProviders.map(serializeLlm),
          incidentDefaults,
          envVariables: envVariables.map(serializeEnvVar),
        }),
      });
      if (!response.ok) {
        setError(await extractApiError(response));
        return;
      }
      setMessage('Settings saved.');
      await loadSettings();
    } catch {
      setError(SIMPLE_ERROR_MESSAGE);
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return <div className="loading-state" style={{ padding: 80 }}>Loading settings…</div>;
  }

  return (
    <div className="content">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 24, gap: 12 }}>
        <div>
          <div style={eyebrowStyle}>SETTINGS</div>
          <div style={titleStyle}>Integrations and LLM APIs</div>
        </div>
        <div style={{ display: 'flex', gap: 10 }}>
          <button className="btn btn-modify" onClick={loadSettings} style={{ fontSize: 11 }}>⟳ REFRESH</button>
          <button onClick={saveSettings} disabled={saving} style={{ ...primaryButtonStyle, opacity: saving ? 0.6 : 1 }}>
            {saving ? 'SAVING...' : 'SAVE SETTINGS'}
          </button>
        </div>
      </div>

      {error && <div className="error-banner" style={{ marginBottom: 14 }}>⚠ {error}</div>}
      {message && <div style={successStyle}>{message}</div>}

      <div style={layoutStyle}>
        <Section title="Incident Source APIs" subtitle="Add ServiceNow, Freshservice, or any other incident source API connection.">
          <div style={{ display: 'grid', gap: 14 }}>
            {incidentSources.map((source, index) => (
              <div key={index} style={panelStyle}>
                <div style={rowStyle}>
                  <input value={source.name} onChange={e => updateSource(index, 'name', e.target.value, setIncidentSources)} placeholder="Connection name" style={inputStyle} />
                  <select value={source.type} onChange={e => updateSource(index, 'type', e.target.value, setIncidentSources)} style={inputStyle}>
                    {['SERVICENOW', 'FRESHSERVICE', 'JIRA', 'PAGERDUTY', 'ZENDESK', 'OTHER'].map(option => <option key={option} value={option}>{option}</option>)}
                  </select>
                  <label style={checkboxWrapStyle}>
                    <input type="checkbox" checked={source.enabled} onChange={e => updateSource(index, 'enabled', e.target.checked, setIncidentSources)} />
                    Enabled
                  </label>
                </div>
                <div style={rowStyle}>
                  <input value={source.baseUrl} onChange={e => updateSource(index, 'baseUrl', e.target.value, setIncidentSources)} placeholder="Base URL" style={inputStyle} />
                  <select value={source.authType} onChange={e => updateSource(index, 'authType', e.target.value, setIncidentSources)} style={inputStyle}>
                    {['api_key', 'basic', 'bearer', 'oauth2', 'none'].map(option => <option key={option} value={option}>{option}</option>)}
                  </select>
                  <input value={source.tenant} onChange={e => updateSource(index, 'tenant', e.target.value, setIncidentSources)} placeholder="Tenant / domain" style={inputStyle} />
                </div>
                <div style={rowStyle}>
                  <input value={source.username} onChange={e => updateSource(index, 'username', e.target.value, setIncidentSources)} placeholder="Username / client id" style={inputStyle} />
                  <input value={source.apiKey} onChange={e => updateSource(index, 'apiKey', e.target.value, setIncidentSources)} placeholder="API key / token" style={inputStyle} />
                </div>
                <textarea value={source.extraConfig} onChange={e => updateSource(index, 'extraConfig', e.target.value, setIncidentSources)} rows={4} style={{ ...inputStyle, resize: 'vertical', fontFamily: 'var(--mono)', minHeight: 90 }} placeholder='Extra JSON config, headers, query params, webhook values...' />
                <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
                  <button onClick={() => setIncidentSources(current => current.filter((_, itemIndex) => itemIndex !== index))} style={dangerButtonStyle}>REMOVE SOURCE</button>
                </div>
              </div>
            ))}
            <button onClick={() => setIncidentSources(current => [...current, newSource()])} style={secondaryButtonStyle}>+ ADD INCIDENT SOURCE</button>
          </div>
        </Section>

        <Section title="LLM API Providers" subtitle="Choose OpenAI, Ollama, Gemini, Anthropic, or any custom LLM API. Generic fields are available for nonstandard providers too.">
          <div style={{ display: 'grid', gap: 14 }}>
            {llmProviders.map((provider, index) => (
              <div key={index} style={panelStyle}>
                <div style={rowStyle}>
                  <input value={provider.name} onChange={e => updateLlm(index, 'name', e.target.value, setLlmProviders)} placeholder="Provider label" style={inputStyle} />
                  <select value={provider.providerType} onChange={e => updateLlm(index, 'providerType', e.target.value, setLlmProviders)} style={inputStyle}>
                    {['OPENAI', 'OLLAMA', 'GEMINI', 'ANTHROPIC', 'AZURE_OPENAI', 'GROQ', 'TOGETHER', 'CUSTOM'].map(option => <option key={option} value={option}>{option}</option>)}
                  </select>
                  <select value={provider.protocol} onChange={e => updateLlm(index, 'protocol', e.target.value, setLlmProviders)} style={inputStyle}>
                    {['openai-compatible', 'native', 'custom-http'].map(option => <option key={option} value={option}>{option}</option>)}
                  </select>
                  <label style={checkboxWrapStyle}>
                    <input type="checkbox" checked={provider.enabled} onChange={e => updateLlm(index, 'enabled', e.target.checked, setLlmProviders)} />
                    Enabled
                  </label>
                </div>
                <div style={rowStyle}>
                  <input value={provider.apiUrl} onChange={e => updateLlm(index, 'apiUrl', e.target.value, setLlmProviders)} placeholder="API URL / base endpoint" style={inputStyle} />
                  <input value={provider.apiKey} onChange={e => updateLlm(index, 'apiKey', e.target.value, setLlmProviders)} placeholder="API key" style={inputStyle} />
                </div>
                <div style={rowStyle}>
                  <input value={provider.model} onChange={e => updateLlm(index, 'model', e.target.value, setLlmProviders)} placeholder="Chat / completion model" style={inputStyle} />
                  <input value={provider.embeddingModel} onChange={e => updateLlm(index, 'embeddingModel', e.target.value, setLlmProviders)} placeholder="Embedding model" style={inputStyle} />
                </div>
                <div style={rowStyle}>
                  <textarea value={provider.headersJson} onChange={e => updateLlm(index, 'headersJson', e.target.value, setLlmProviders)} rows={4} style={{ ...inputStyle, resize: 'vertical', fontFamily: 'var(--mono)', minHeight: 90 }} placeholder='Custom headers JSON. Example: {"x-api-key":"abc"}' />
                  <textarea value={provider.optionsJson} onChange={e => updateLlm(index, 'optionsJson', e.target.value, setLlmProviders)} rows={4} style={{ ...inputStyle, resize: 'vertical', fontFamily: 'var(--mono)', minHeight: 90 }} placeholder='Extra options JSON. Example: {"temperature":0.1,"max_tokens":1024}' />
                </div>
                <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
                  <button onClick={() => setLlmProviders(current => current.filter((_, itemIndex) => itemIndex !== index))} style={dangerButtonStyle}>REMOVE PROVIDER</button>
                </div>
              </div>
            ))}
            <button onClick={() => setLlmProviders(current => [...current, newLlm()])} style={secondaryButtonStyle}>+ ADD LLM PROVIDER</button>
          </div>
        </Section>
      </div>

      <div style={{ marginTop: 22 }}>
        <Section title="Incident Intake Defaults" subtitle="Set the default source for dashboard incident creation and whether new incidents should auto-start processing.">
          <div style={panelStyle}>
            <div style={rowStyle}>
              <select value={incidentDefaults.defaultSourceSystem} onChange={e => setIncidentDefaults(current => ({ ...current, defaultSourceSystem: e.target.value }))} style={inputStyle}>
                {sourceOptions.map(option => <option key={option} value={option}>{option}</option>)}
              </select>
              <label style={checkboxWrapStyle}>
                <input type="checkbox" checked={incidentDefaults.autoProcessOnCreate} onChange={e => setIncidentDefaults(current => ({ ...current, autoProcessOnCreate: e.target.checked }))} />
                Auto-process new incidents
              </label>
            </div>
          </div>
        </Section>
      </div>

      <div style={{ marginTop: 22 }}>
        <Section title="Execution Environment Variables" subtitle="Variables defined here are intended for execution-time injection only. Secret values should not be sent back to the LLM or exposed in chat.">
          <div style={{ display: 'grid', gap: 14 }}>
            {envVariables.map((envVar, index) => (
              <div key={envVar.id || index} style={panelStyle}>
                <div style={rowStyle}>
                  <input value={envVar.key} onChange={e => updateEnvVar(index, 'key', e.target.value, setEnvVariables)} placeholder="Variable key" style={inputStyle} />
                  <select value={envVar.scope} onChange={e => updateEnvVar(index, 'scope', e.target.value, setEnvVariables)} style={inputStyle}>
                    {['GLOBAL', 'TENANT', 'SERVICE', 'HOST_GROUP'].map(option => <option key={option} value={option}>{option}</option>)}
                  </select>
                  <input value={envVar.targetEnvironment} onChange={e => updateEnvVar(index, 'targetEnvironment', e.target.value, setEnvVariables)} placeholder="Target environment" style={inputStyle} />
                </div>
                <div style={rowStyle}>
                  <input value={envVar.value} onChange={e => updateEnvVar(index, 'value', e.target.value, setEnvVariables)} placeholder={envVar.secret ? 'Secret value' : 'Value'} style={inputStyle} />
                  <input value={envVar.description} onChange={e => updateEnvVar(index, 'description', e.target.value, setEnvVariables)} placeholder="Description / usage" style={inputStyle} />
                  <label style={checkboxWrapStyle}>
                    <input type="checkbox" checked={envVar.secret} onChange={e => updateEnvVar(index, 'secret', e.target.checked, setEnvVariables)} />
                    Secret
                  </label>
                </div>
                {envVar.maskedValue && <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>Stored mask: {envVar.maskedValue}</div>}
                <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
                  <button onClick={() => setEnvVariables(current => current.filter((_, itemIndex) => itemIndex !== index))} style={dangerButtonStyle}>REMOVE VARIABLE</button>
                </div>
              </div>
            ))}
            <button onClick={() => setEnvVariables(current => [...current, newEnvVar()])} style={secondaryButtonStyle}>+ ADD ENV VARIABLE</button>
          </div>
        </Section>
      </div>
    </div>
  );
};

const deserializeSource = (raw: any): SourceConfig => ({
  name: raw?.name || '',
  type: raw?.type || 'SERVICENOW',
  baseUrl: raw?.baseUrl || '',
  authType: raw?.authType || 'api_key',
  username: raw?.username || '',
  apiKey: raw?.apiKey || '',
  tenant: raw?.tenant || '',
  enabled: raw?.enabled !== false,
  extraConfig: JSON.stringify(raw?.extraConfig ?? {}, null, 2),
});

const serializeSource = (raw: SourceConfig) => ({
  name: raw.name.trim(),
  type: raw.type,
  baseUrl: raw.baseUrl.trim(),
  authType: raw.authType,
  username: raw.username.trim(),
  apiKey: raw.apiKey,
  tenant: raw.tenant.trim(),
  enabled: raw.enabled,
  extraConfig: safeJson(raw.extraConfig),
});

const deserializeLlm = (raw: any): LlmConfig => ({
  name: raw?.name || '',
  providerType: raw?.providerType || 'OPENAI',
  protocol: raw?.protocol || 'openai-compatible',
  apiUrl: raw?.apiUrl || '',
  apiKey: raw?.apiKey || '',
  model: raw?.model || '',
  embeddingModel: raw?.embeddingModel || '',
  enabled: raw?.enabled !== false,
  headersJson: JSON.stringify(raw?.headers ?? {}, null, 2),
  optionsJson: JSON.stringify(raw?.options ?? {}, null, 2),
});

const serializeLlm = (raw: LlmConfig) => ({
  name: raw.name.trim(),
  providerType: raw.providerType,
  protocol: raw.protocol,
  apiUrl: raw.apiUrl.trim(),
  apiKey: raw.apiKey,
  model: raw.model.trim(),
  embeddingModel: raw.embeddingModel.trim(),
  enabled: raw.enabled,
  headers: safeJson(raw.headersJson),
  options: safeJson(raw.optionsJson),
});

const deserializeEnvVar = (raw: any): EnvVariableConfig => ({
  id: raw?.id,
  key: raw?.key || '',
  value: raw?.value || '',
  maskedValue: raw?.maskedValue || '',
  secret: raw?.secret !== false,
  scope: raw?.scope || 'TENANT',
  targetEnvironment: raw?.targetEnvironment || 'default',
  description: raw?.description || '',
});

const serializeEnvVar = (raw: EnvVariableConfig) => ({
  id: raw.id,
  key: raw.key.trim(),
  value: raw.value,
  maskedValue: raw.maskedValue || '',
  secret: raw.secret,
  scope: raw.scope,
  targetEnvironment: raw.targetEnvironment.trim() || 'default',
  description: raw.description.trim(),
});

const safeJson = (value: string) => {
  try {
    return value.trim() ? JSON.parse(value) : {};
  } catch {
    return { raw: value };
  }
};

const updateSource = (index: number, key: keyof SourceConfig, value: string | boolean, setState: React.Dispatch<React.SetStateAction<SourceConfig[]>>) => {
  setState(current => current.map((item, itemIndex) => itemIndex === index ? { ...item, [key]: value } : item));
};

const updateLlm = (index: number, key: keyof LlmConfig, value: string | boolean, setState: React.Dispatch<React.SetStateAction<LlmConfig[]>>) => {
  setState(current => current.map((item, itemIndex) => itemIndex === index ? { ...item, [key]: value } : item));
};

const updateEnvVar = (index: number, key: keyof EnvVariableConfig, value: string | boolean, setState: React.Dispatch<React.SetStateAction<EnvVariableConfig[]>>) => {
  setState(current => current.map((item, itemIndex) => itemIndex === index ? { ...item, [key]: value } : item));
};

const Section: React.FC<{ title: string; subtitle: string; children: React.ReactNode }> = ({ title, subtitle, children }) => (
  <div style={{ background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 12, padding: 18 }}>
    <div style={sectionTitleStyle}>{title}</div>
    <div style={{ color: 'var(--text-muted)', fontSize: 12, marginBottom: 14, lineHeight: 1.5 }}>{subtitle}</div>
    {children}
  </div>
);

const layoutStyle: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: 'repeat(2, minmax(0, 1fr))',
  gap: 20,
  alignItems: 'start',
};

const panelStyle: React.CSSProperties = {
  border: '1px solid var(--border)',
  background: 'var(--surface2)',
  borderRadius: 10,
  padding: 14,
  display: 'grid',
  gap: 12,
};

const rowStyle: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: 'repeat(3, minmax(0, 1fr))',
  gap: 12,
};

const eyebrowStyle: React.CSSProperties = {
  fontFamily: 'var(--mono)',
  fontSize: 11,
  color: 'var(--text-muted)',
  letterSpacing: 2,
  textTransform: 'uppercase',
  marginBottom: 4,
};

const titleStyle: React.CSSProperties = {
  fontFamily: 'var(--mono)',
  fontSize: 20,
  fontWeight: 700,
  color: 'var(--text)',
};

const sectionTitleStyle: React.CSSProperties = {
  fontFamily: 'var(--mono)',
  fontSize: 14,
  fontWeight: 700,
  color: 'var(--text)',
  marginBottom: 6,
};

const inputStyle: React.CSSProperties = {
  width: '100%',
  background: 'var(--surface3)',
  border: '1px solid var(--border)',
  borderRadius: 8,
  color: 'var(--text)',
  padding: '10px 12px',
  fontSize: 12,
  outline: 'none',
  boxSizing: 'border-box',
};

const checkboxWrapStyle: React.CSSProperties = {
  display: 'flex',
  gap: 8,
  alignItems: 'center',
  color: 'var(--text-dim)',
  fontSize: 12,
  minHeight: 40,
};

const primaryButtonStyle: React.CSSProperties = {
  padding: '9px 16px',
  borderRadius: 8,
  fontSize: 12,
  fontFamily: 'var(--mono)',
  fontWeight: 700,
  cursor: 'pointer',
  border: '1px solid rgba(79,142,247,0.4)',
  background: 'var(--blue-dim)',
  color: 'var(--blue)',
};

const secondaryButtonStyle: React.CSSProperties = {
  ...primaryButtonStyle,
  background: 'var(--surface2)',
  color: 'var(--text-dim)',
  border: '1px solid var(--border-bright)',
};

const dangerButtonStyle: React.CSSProperties = {
  ...primaryButtonStyle,
  background: 'rgba(255,85,85,0.08)',
  color: 'var(--red)',
  border: '1px solid rgba(255,85,85,0.28)',
};

const successStyle: React.CSSProperties = {
  marginBottom: 14,
  padding: '10px 12px',
  borderRadius: 8,
  border: '1px solid rgba(48,217,156,0.28)',
  background: 'rgba(48,217,156,0.08)',
  color: 'var(--green)',
  fontSize: 12,
};

export default SettingsPage;
