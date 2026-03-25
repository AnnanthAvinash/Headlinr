#!/usr/bin/env python3
"""Create HeadlinrApp project in Jira via REST API. Reads credentials from ~/.cursor/mcp.json."""
import json
import base64
import urllib.request
import urllib.error
import os

mcp_path = os.path.join(os.path.expanduser("~"), ".cursor", "mcp.json")
with open(mcp_path) as f:
    config = json.load(f)
servers = config.get("mcpServers", {})
env = (servers.get("mcp-atlassian") or servers.get("user-mcp-atlassian") or {}).get("env", {})

url = env["JIRA_URL"].rstrip("/") + "/rest/api/3/project"
auth = base64.b64encode(f"{env['JIRA_USERNAME']}:{env['JIRA_API_TOKEN']}".encode()).decode()

# Get current user's accountId for project lead
verify_req = urllib.request.Request(env["JIRA_URL"].rstrip("/") + "/rest/api/3/myself", method="GET")
verify_req.add_header("Authorization", f"Basic {auth}")
try:
    with urllib.request.urlopen(verify_req, timeout=10) as resp:
        myself = json.loads(resp.read().decode())
        lead_account_id = myself.get("accountId")
except urllib.error.HTTPError as e:
    if e.code == 401:
        print("Auth failed: invalid/expired API token. Create a new token at:")
        print("  https://id.atlassian.com/manage-profile/security/api-tokens")
        exit(1)
    raise

data = json.dumps({
    "key": "HLNAPP",
    "name": "HeadlinrApp",
    "projectTypeKey": "software",
    "projectTemplateKey": "com.pyxis.greenhopper.jira:gh-simplified-agility-scrum",
    "description": "Headlinr Android news app - RSS, categories, trending",
    "leadAccountId": lead_account_id,
}).encode()

req = urllib.request.Request(url, data=data, method="POST")
req.add_header("Content-Type", "application/json")
req.add_header("Authorization", f"Basic {auth}")

try:
    with urllib.request.urlopen(req, timeout=30) as resp:
        result = json.loads(resp.read().decode())
        print(f"Created project: {result.get('key')} - {result.get('name')}")
        print(f"URL: {env['JIRA_URL']}browse/{result.get('key')}")
except urllib.error.HTTPError as e:
    body = e.read().decode() if e.fp else ""
    print(f"Error {e.code}: {e.reason}")
    print(body[:500])
except Exception as e:
    print(f"Error: {e}")
