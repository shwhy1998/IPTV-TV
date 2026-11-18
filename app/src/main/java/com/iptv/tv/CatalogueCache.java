package com.iptv.tv;

import android.util.AtomicFile;
import java.io.*;

final class CatalogueCache {
    private final AtomicFile file;

    CatalogueCache(File directory) {
        file = new AtomicFile(new File(directory, "catalogue"));
    }

    Playlist read(String source) {
        try (ObjectInputStream stream = new ObjectInputStream(new BufferedInputStream(file.openRead()))) {
            return source.equals(stream.readUTF()) ? (Playlist) stream.readObject() : null;
        } catch (IOException | ClassNotFoundException ignored) {
            return null;
        }
    }

    void write(String source, Playlist playlist) {
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            ObjectOutputStream stream = new ObjectOutputStream(new BufferedOutputStream(output));
            stream.writeUTF(source);
            stream.writeObject(playlist);
            stream.flush();
            file.finishWrite(output);
        } catch (IOException failure) {
            if (output != null) file.failWrite(output);
        }
    }

    void clear() {
        file.delete();
    }
}
