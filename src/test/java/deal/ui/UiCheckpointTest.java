package deal.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/** Production downstream compilation and real JS session/checkpoint execution. */
public final class UiCheckpointTest {
    private UiCheckpointTest() {}
    public static void main(String[] args) throws Exception {
        Path root=Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path stage=root.resolve("build/checkpoint-test"),src=stage.resolve("src");Files.createDirectories(src);
        Path fixtures=root.resolve("src/test/fixtures/js-session");
        for(String name:new String[]{"app.deal","callbacks.deal","checkpoint.deal"})Files.copy(fixtures.resolve(name),src.resolve(name),StandardCopyOption.REPLACE_EXISTING);
        String pack="export class ButtonProps { text:string; accessibilityLabel:string = \"\"; onClick?:Action; }\nexport component Button(props:ButtonProps):View { event onClick; capability \"renderer.test.button\"; }\nexport class ColumnProps {}\nexport component Column(props:ColumnProps):View { children; capability \"renderer.test.column\"; }\n";
        pack += "export class IntProps { value:number; }\nexport component IntText(props:IntProps):View { capability \"renderer.test.number\"; }\n";
        Path packFile=stage.resolve("platform.dealui-pack");Files.writeString(packFile,pack);
        String app=Files.readString(src.resolve("app.deal")),view=Files.readString(fixtures.resolve("app.dealui"));
        var generated=UiSourceGenerator.generate(src.resolve("app.dealui"),view,src.resolve("app.deal"),app,Map.of("./platform.dealui-pack",UiParser.parsePack(packFile,pack)),root);
        Files.writeString(src.resolve("app.deal"),UiSourceGenerator.compilerSource(app)+generated.augmentation());
        Files.writeString(src.resolve("experience_entry.deal"),"import * as proof from \"./checkpoint\";\n"+generated.source()+"\nexport function proofInitial():proof.State{return proof.initial();}\nexport function proofVerify(s:proof.State):void{proof.verify(s);}\nexport function proofRecursive():proof.Node{return proof.recursive();}\n");
        Files.writeString(stage.resolve("work.d.deal"),"export function run(value:number):Promise<number>;");
        Files.writeString(stage.resolve("deal.json"),"{\"languageVersion\":\"1.6\",\"moduleRoots\":[\"src\"],\"externals\":{\"host/work\":{\"declaration\":\"work.d.deal\"}}}");
        Path output=stage.resolve("out");
        if(UiCheckpointCompiler.compile(src.resolve("experience_entry.deal"),output,stage.resolve("diagnostics.json"))!=0)throw new AssertionError("Checkpoint compilation failed");
        Path host=Path.of(args[0]).toAbsolutePath();
        Process child=new ProcessBuilder("node",root.resolve("src/test/js/checkpoint.test.js").toString(),output.toString(),host.toString()).inheritIO().start();
        if(child.waitFor()!=0)throw new AssertionError("Checkpoint test failed");
        child=new ProcessBuilder("node",root.resolve("src/test/js/session.test.js").toString(),output.toString(),host.toString()).inheritIO().start();
        if(child.waitFor()!=0)throw new AssertionError("Session lifecycle test failed");
    }
}
