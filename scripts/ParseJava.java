import com.sun.source.util.JavacTask;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Syntax parsing only: the Android SDK is required for type checking and APK compilation. */
class ParseJava {
    public static void main(String[] args) throws Exception {
        JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();
        if(compiler==null) throw new IllegalStateException("JDK compiler required");
        List<String> files=new ArrayList<>();
        try(var paths=Files.walk(Path.of(args.length>0?args[0]:"app/src/main/java"))) {
            paths.filter(p->p.toString().endsWith(".java")).forEach(p->files.add(p.toString()));
        }
        DiagnosticCollector<JavaFileObject> diagnostics=new DiagnosticCollector<>();
        try(StandardJavaFileManager manager=compiler.getStandardFileManager(diagnostics,null,null)) {
            JavacTask task=(JavacTask)compiler.getTask(null,manager,diagnostics,List.of("-proc:none","-source","17"),null,manager.getJavaFileObjectsFromStrings(files));
            task.parse();
        }
        for(var diagnostic:diagnostics.getDiagnostics()) {
            if(diagnostic.getKind()==Diagnostic.Kind.ERROR) throw new IllegalStateException(diagnostic.toString());
        }
        System.out.println("Java syntax parsed: "+files.size()+" files. Android type checking still requires the SDK.");
    }
}
