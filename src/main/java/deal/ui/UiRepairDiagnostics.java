package deal.ui;

import deal.compiler.RepairDiagnosticRegistry;
import java.util.List;
import static deal.compiler.RepairDiagnosticRegistry.Category.*;

/** UI syntax, framework and cross-artifact diagnostic inventory owned by the UI frontend. */
public final class UiRepairDiagnostics {
    private UiRepairDiagnostics() {}
    public static RepairDiagnosticRegistry registry() {
        return RepairDiagnosticRegistry.core()
                .extend(RUNTIME, List.of("UI3001", "UI3002", "UI3004"))
                .extend(UI_SYNTAX, List.of("UI1001", "UI1002", "UI1003", "UI1004", "UI1005", "UI1006", "UI1007",
                        "UI1008", "UI1009", "UI1010", "UI1011", "UI1012", "UI1013", "UI1014", "UI1015"))
                .extend(UI_CONTRACT, List.of("UI2001", "UI2002", "UI2003", "UI2004", "UI2005", "UI2006", "UI2007",
                        "UI2008", "UI2010", "UI2011", "UI2012", "UI2013", "UI2014", "UI2015", "UI2016", "UI2017",
                        "UI2018", "UI2019", "UI2020", "UI2021", "UI2022", "UI2023", "UI2024", "UI2025", "UI2026",
                        "UI2027", "UI2028", "UI2029", "UI2030", "UI2031", "UI2032", "UI2033", "UI2034", "UI2035",
                        "UI2036", "UI2037", "UI2038", "UI2039", "UI2040", "UI2041", "UI2042", "UI2043", "UI2044",
                        "UI2045", "UI2046", "UI2047", "UI2048", "UI2049", "UI2050", "UI2051", "UI2052", "UI2060", "UI2061"))
                .extend(PROTOCOL, List.of("CP2001", "CP2002", "CP2003", "CP2004", "CP2020", "CP2021", "CP2022",
                        "CP2023", "CP2024", "CP2025", "CP2026", "CP2999"));
    }
}
