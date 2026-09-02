-- Provider credentials are environment-only. Remove the legacy reversible value.
DELETE FROM config.system_config WHERE config_key = 'api_key';