#!/usr/bin/env python3
"""Update Jira issue HLNAPP-22 with quality gate expected criteria. Reads credentials from ~/.cursor/mcp.json."""
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

base_url = env["JIRA_URL"].rstrip("/")
auth = base64.b64encode(f"{env['JIRA_USERNAME']}:{env['JIRA_API_TOKEN']}".encode()).decode()

# Expected criteria description (Atlassian Document Format)
description_adf = {
    "type": "doc",
    "version": 1,
    "content": [
        {"type": "paragraph", "content": [{"type": "text", "text": "Expected criteria (quality gate):"}]},
        {"type": "bulletList", "content": [
            {"type": "listItem", "content": [{"type": "paragraph", "content": [{"type": "text", "text": "Description: min 250 chars, max 500 chars (truncated)"}]}]},
            {"type": "listItem", "content": [{"type": "paragraph", "content": [{"type": "text", "text": "Image: rules unchanged (valid URL, ≥600px, no placeholders/logos)"}]}]},
        ]},
    ],
}

body = json.dumps({"fields": {"description": description_adf}}).encode()
req = urllib.request.Request(f"{base_url}/rest/api/3/issue/HLNAPP-22", data=body, method="PUT")
req.add_header("Content-Type", "application/json")
req.add_header("Authorization", f"Basic {auth}")

try:
    with urllib.request.urlopen(req, timeout=15) as resp:
        print("Updated HLNAPP-22 description")
        print(f"URL: {base_url}/browse/HLNAPP-22")
except urllib.error.HTTPError as e:
    body_err = e.read().decode() if e.fp else ""
    print(f"Error {e.code}: {e.reason}")
    print(body_err[:800])
    exit(1)
except Exception as e:
    print(f"Error: {e}")
    exit(1)
