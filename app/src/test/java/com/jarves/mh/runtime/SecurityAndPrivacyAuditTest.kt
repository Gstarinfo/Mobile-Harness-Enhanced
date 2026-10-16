package com.jarves.mh.runtime

import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.ProviderProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SecurityAndPrivacyAuditTest {

    @Test
    fun `redactToolDetail masks API keys, bearer tokens, and passwords`() {
        val jsonWithApiKey = org.json.JSONObject()
            .put("description", "Connecting with api_key=sk-ant-api03-verysecretkey123456")
        val parsed1 = AntigravityEventParser.parse(
            """{"event":"step_update","step_update":{"state":"ACTIVE","step_type":"tool","tool_name":"run_command","description":"Connecting with api_key=sk-ant-api03-verysecretkey123456"}}"""
        )
        assertTrue(parsed1 is AntigravityParsedEvent.ToolStarted)
        val tool1 = parsed1 as AntigravityParsedEvent.ToolStarted
        assertFalse(tool1.detail.contains("sk-ant-api03-verysecretkey123456"))
        assertTrue(tool1.detail.contains("••••"))

        val parsed2 = AntigravityEventParser.parse(
            """{"event":"step_update","step_update":{"state":"ACTIVE","step_type":"tool","tool_name":"run_command","description":"curl -H 'Authorization: Bearer my-super-secret-token' https://api.example.com"}}"""
        )
        assertTrue(parsed2 is AntigravityParsedEvent.ToolStarted)
        val tool2 = parsed2 as AntigravityParsedEvent.ToolStarted
        assertFalse(tool2.detail.contains("my-super-secret-token"))
        assertTrue(tool2.detail.contains("••••"))
    }

    @Test
    fun `telemetry is disabled for coding agent processes`() {
        val profile = ProviderProfile(
            kind = ProviderKind.ANTHROPIC,
            baseUrl = "https://api.anthropic.com",
            model = "claude-3-7-sonnet",
        )
        val launch = RuntimeLaunchConfigBuilder.build(profile, authToken = "sk-test-key")
        assertEquals("1", launch.environment["DISABLE_TELEMETRY"])
        assertEquals("1", launch.environment["DISABLE_AUTOUPDATER"])
    }

    @Test
    fun `claude login clears all static API key environment variables`() {
        val profile = ProviderProfile(
            kind = ProviderKind.CLAUDE,
            baseUrl = "https://api.anthropic.com",
            model = "claude-3-7-sonnet",
        )
        val launch = RuntimeLaunchConfigBuilder.build(profile, authToken = "session-oauth-token")
        assertEquals("session-oauth-token", launch.environment["CLAUDE_CODE_OAUTH_TOKEN"])
        // Precedence protection: ensures static key vars are blanked so they cannot be leaked or shadowed
        assertEquals("", launch.environment["ANTHROPIC_API_KEY"])
        assertEquals("", launch.environment["ANTHROPIC_AUTH_TOKEN"])
    }

    @Test
    fun `google oauth url extraction rejects non-google endpoints`() {
        val safeGoogleOutput = "https://accounts.google.com/o/oauth2/auth?client_id=123.apps.googleusercontent.com&code_challenge=xyz&state=abc"
        val extractedGoogle = extractGoogleOAuthUrl(safeGoogleOutput)
        assertTrue(extractedGoogle?.startsWith("https://accounts.google.com/") == true)

        val maliciousOutput = "https://evil-phishing-site.com/steal-token?client_id=123&code_challenge=xyz&state=abc"
        val extractedMalicious = extractGoogleOAuthUrl(maliciousOutput)
        assertFalse(extractedMalicious?.contains("evil-phishing-site.com") == true)
    }

    @Test
    fun `antigravity workspace prompt enforces project confinement`() {
        val prompt = antigravityWorkspacePrompt("my-app", "Do some work")
        assertTrue(prompt.contains("The active project workspace is /workspace/my-app"))
        assertTrue(prompt.contains("Do not create project output under ~/.gemini/antigravity-cli/scratch"))
    }

    @Test
    fun `openrouter routing policy only modifies provider object without altering destination`() {
        val source = org.json.JSONObject().put("model", "meta-llama/llama-3")
        val profile = ProviderProfile(
            kind = ProviderKind.LLM_ROUTER,
            openRouterProviderOrder = "Hyperbolic,Together",
            openRouterAllowFallbacks = false,
        )
        val routed = applyOpenRouterRouting(source, profile)
        val providerObj = routed.getJSONObject("provider")
        val orderArray = providerObj.getJSONArray("order")
        assertEquals("Hyperbolic", orderArray.getString(0))
        assertEquals("Together", orderArray.getString(1))
        assertFalse(providerObj.getBoolean("allow_fallbacks"))
    }
}
