package io.github.randomcodespace.iq.detector.shell;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PowerShellDetectorTest {

    private final PowerShellDetector d = new PowerShellDetector();

    @Test
    void detectsFunction() {
        String code = "function Get-Users {\n    param()\n    Get-ADUser -Filter *\n}\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.METHOD && "Get-Users".equals(n.getLabel())));
    }

    @Test
    void detectsMultipleFunctions() {
        String code = """
                function Get-Users { }
                function Set-Config { }
                function Remove-OldData { }
                """;
        DetectorResult r = d.detect(ctx(code));
        assertEquals(3, r.nodes().stream().filter(n -> n.getKind() == NodeKind.METHOD).count());
    }

    @Test
    void detectsCaseInsensitiveFunction() {
        String code = "Function Get-Data {\n    Write-Host \"hi\"\n}\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.METHOD));
    }

    @Test
    void detectsImportModule() {
        String code = "Import-Module Az\nImport-Module ActiveDirectory\n";
        DetectorResult r = d.detect(ctx(code));
        assertEquals(2, r.edges().stream().filter(e -> e.getKind() == EdgeKind.IMPORTS).count());
    }

    @Test
    void detectsDotSource() {
        String code = ". ./helpers.ps1\n. ./config.psm1\n";
        DetectorResult r = d.detect(ctx(code));
        assertEquals(2, r.edges().stream().filter(e -> e.getKind() == EdgeKind.IMPORTS).count());
    }

    @Test
    void detectsDotSourceWithQuotes() {
        String code = ". \"./helpers.ps1\"\n. './config.psm1'\n";
        DetectorResult r = d.detect(ctx(code));
        assertEquals(2, r.edges().stream().filter(e -> e.getKind() == EdgeKind.IMPORTS).count());
    }

    @Test
    void detectsAdvancedFunction() {
        // CmdletBinding after function definition -> marks as advanced
        String code = """
                function Get-Report {
                    [CmdletBinding()]
                    param()
                    Get-Content report.txt
                }
                """;
        DetectorResult r = d.detect(ctx(code));
        var fn = r.nodes().stream().filter(n -> n.getKind() == NodeKind.METHOD).findFirst().orElseThrow();
        assertEquals(true, fn.getProperties().get("advanced_function"));
    }

    @Test
    void emptyContentReturnsEmpty() {
        DetectorResult r = d.detect(ctx(""));
        assertTrue(r.nodes().isEmpty());
        assertTrue(r.edges().isEmpty());
    }

    @Test
    void nullContentReturnsEmpty() {
        DetectorContext ctxNull = new DetectorContext("test.ps1", "powershell", null);
        DetectorResult r = d.detect(ctxNull);
        assertTrue(r.nodes().isEmpty());
    }

    @Test
    void returnsCorrectName() {
        assertEquals("powershell", d.getName());
    }

    @Test
    void supportedLanguagesContainsPowershell() {
        assertTrue(d.getSupportedLanguages().contains("powershell"));
    }

    @Test
    void deterministic() {
        String code = """
                Import-Module Az
                Import-Module ActiveDirectory
                . ./shared.ps1
                function Get-AllUsers {
                    [CmdletBinding()]
                    param()
                    Get-ADUser -Filter * | Select-Object Name, Email
                }
                function Set-UserConfig {
                    param([string]$Username)
                    Write-Host "Configuring $Username"
                }
                function Remove-StaleData {
                    Import-Module SqlServer
                    Invoke-Sqlcmd -Query "DELETE FROM stale"
                }
                """;
        DetectorTestUtils.assertDeterministic(d, ctx(code));
    }

    private static DetectorContext ctx(String content) {
        return DetectorTestUtils.contextFor("powershell", content);
    }
}
