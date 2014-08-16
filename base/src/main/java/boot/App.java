package boot;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.RandomAccessFile;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.channels.FileLock;
import java.nio.channels.FileChannel;

import java.net.URL;
import java.net.URLClassLoader;

import org.projectodd.shimdandy.ClojureRuntimeShim;

import java.util.HashMap;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Callable;

public class App {
    private static File[] podjars;
    private static File   bootdir;
    private static File   aetherfile;
    
    private static final String appversion = "2.0.0";
    private static final String apprelease = "r1";
    private static final String depversion = appversion + "-SNAPSHOT";
    private static final String aetherjar  = "aether-" + depversion + "-standalone.jar";

    public static ClojureRuntimeShim core;
    public static ClojureRuntimeShim aether;

    public static ClojureRuntimeShim getCore()    { return core; }
    public static ClojureRuntimeShim getAether()  { return aether; }
    public static File               getBootDir() { return bootdir; }
    public static String             getVersion() { return appversion; }
    public static String             getRelease() { return apprelease; }
    
    public static void writeCache(File f, Object m) throws Exception {
        FileOutputStream out = new FileOutputStream(f);
        (new ObjectOutputStream(out)).writeObject(m);
        out.close(); }
    
    public static HashMap<String, File[]> seedCache(ClojureRuntimeShim a) throws Exception {
        if (a == null) {
            ensureResourceFile(aetherjar, aetherfile);
            a = newShim(new File[] { aetherfile }); }
        
        HashMap<String, File[]> cache = new HashMap<String, File[]>();
        
        cache.put("boot/pod",    getDeps(a, "boot/pod"));
        cache.put("boot/core",   getDeps(a, "boot/core"));
        cache.put("boot/aether", getDeps(a, "boot/aether"));

        return cache; }
    
    public static Object readCache(File f) throws Exception {
        try {
            long age = System.currentTimeMillis() - f.lastModified();
            long max = 18 * 60 * 60 * 1000;
            if (age > max) throw new Exception("cache age exceeds TTL");
            return (new ObjectInputStream(new FileInputStream(f))).readObject(); }
        catch (Throwable e) { return seedCache(null); }}
    
    public static ClojureRuntimeShim newShim(File[] jarFiles) throws Exception {
        URL[] urls = new URL[jarFiles.length];
        
        for (int i=0; i<jarFiles.length; i++) urls[i] = jarFiles[i].toURI().toURL();
        
        ClassLoader cl = new URLClassLoader(urls, App.class.getClassLoader());
        ClojureRuntimeShim rt = ClojureRuntimeShim.newRuntime(cl);

        rt.require("boot.pod");
        rt.invoke("boot.pod/seal-app-classloader");

        return rt; }
    
    public static ClojureRuntimeShim newPod() throws Exception {
        return newShim(podjars); }
    
    public static ClojureRuntimeShim newPod(File[] jarFiles) throws Exception {
        File[] files = new File[jarFiles.length + podjars.length];
        
        for (int i=0; i<podjars.length; i++) files[i] = podjars[i];
        for (int i=0; i<jarFiles.length; i++) files[i + podjars.length] = jarFiles[i];
        
        return newShim(files); }
    
    public static void extractResource(String resource, File outfile) throws Exception {
        ClassLoader  cl  = Thread.currentThread().getContextClassLoader();
        InputStream  in  = cl.getResourceAsStream(resource);
        OutputStream out = new FileOutputStream(outfile);
        int          n   = 0;
        byte[]       buf = new byte[4096];

        try { while ((n = in.read(buf)) > 0) out.write(buf, 0, n); }
        finally { in.close(); out.close(); }}
    
    public static void ensureResourceFile(String r, File f) throws Exception {
        if (! f.exists()) extractResource(r, f); }
    
    public static File[] getDeps(ClojureRuntimeShim shim, String sym) {
        shim.require("boot.aether");
        return (File[]) shim.invoke("boot.aether/resolve-dependency-jars", sym, depversion); }
    
    public static void setOffline(String x) throws Exception {
        aether.require("boot.aether");
        aether.invoke("boot.aether/set-offline!", x); }
    
    public static void setUpdate(String x) throws Exception {
        aether.require("boot.aether");
        aether.invoke("boot.aether/set-update!", x); }
    
    public static void main(String[] args) throws Exception {
        File homedir    = new File(System.getProperty("user.home"));
        bootdir         = new File(homedir, ".boot");
        File reldir     = new File(bootdir, "lib");
        File jardir     = new File(reldir, apprelease);
        aetherfile      = new File(jardir, aetherjar);
        
        final File cachedir  = new File(bootdir, "cache");
        final File cachefile = new File(cachedir, "deps.cache");
        final File lockfile  = new File(cachedir, "deps.lock");
        
        jardir.mkdirs();
        cachedir.mkdirs();
        
        RandomAccessFile lf = new RandomAccessFile(lockfile, "rw");
        FileLock         ll = lf.getChannel().lock();

        final HashMap<String, File[]> cache = (HashMap<String, File[]>) readCache(cachefile);

        ll.release();
        lf.close();

        podjars = cache.get("boot/pod");
        
        final ExecutorService ex = Executors.newCachedThreadPool();

        final Future f1 = ex.submit(new Callable() {
                public Object call() throws Exception {
                    return newShim(cache.get("boot/aether")); }});

        final Future f2 = ex.submit(new Callable() {
                public Object call() throws Exception {
                    return newShim(cache.get("boot/core")); }});
        
        final Thread t1 = (new Thread() {
                public void run() {
                    try {
                        RandomAccessFile lf = new RandomAccessFile(lockfile, "rw");
                        FileLock         ll = lf.getChannel().lock();
                        writeCache(cachefile, (HashMap<String, File[]>) seedCache((ClojureRuntimeShim) f1.get()));
                        ll.release();
                        lf.close(); }
                    catch (Throwable e) { }}});
        
        t1.start();
        
        Runtime.getRuntime().addShutdownHook(new Thread() {
                public void run() {
                    try { t1.join(); }
                    catch (Throwable e) { }}});

        aether  = (ClojureRuntimeShim) f1.get();
        core    = (ClojureRuntimeShim) f2.get();

        ex.shutdown();
        
        core.require("boot.main");
        core.invoke("boot.main/-main", args);
        
        System.exit(0); }}
