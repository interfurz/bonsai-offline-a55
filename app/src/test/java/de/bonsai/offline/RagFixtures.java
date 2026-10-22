package de.bonsai.offline;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

final class RagFixtures {
    static File install(Context context) throws IOException {
        File directory=new File(context.getFilesDir(),"rag");
        if(!directory.isDirectory() && !directory.mkdirs())throw new IOException("fixture directory");
        copy("knowledge.sqlite",new File(directory,"knowledge.sqlite"));
        copy("vectors.i8",new File(directory,"vectors.i8"));
        Files.deleteIfExists(new File(directory,"config.properties").toPath());
        return directory;
    }
    static void copy(String name,File destination) throws IOException {
        try(InputStream in=RagFixtures.class.getResourceAsStream("/rag/"+name)) {
            if(in==null)throw new IOException("Missing Python fixture: "+name);
            Files.copy(in,destination.toPath(),StandardCopyOption.REPLACE_EXISTING);
        }
    }
    private RagFixtures() {}
}
