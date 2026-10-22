package ro.sw3g.scrcpy;

import android.content.Context;
import android.util.AtomicFile;
import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;

final class AdbIdentity extends AbsAdbConnectionManager {
    private final PrivateKey key;
    private final Certificate cert;
    AdbIdentity(Context ctx) throws Exception {
        Object[] pair = load(ctx);
        key = (PrivateKey) pair[0]; cert = (Certificate) pair[1];
        setApi(30); setTimeout(20, TimeUnit.SECONDS); setThrowOnUnauthorised(false);
    }
    protected PrivateKey getPrivateKey() { return key; }
    protected Certificate getCertificate() { return cert; }
    protected String getDeviceName() { return "ScrcpyOLED"; }
    private static synchronized Object[] load(Context ctx) throws Exception {
        AtomicFile file = new AtomicFile(new File(ctx.getFilesDir(), "adb-identity.bin"));
        if (file.getBaseFile().exists()) {
            try (DataInputStream in = new DataInputStream(file.openRead())) {
                int n=in.readInt(); if(n<1 || n>16384)throw new IOException(I18n.t(R.string.text_067));
                byte[] kb=new byte[n]; in.readFully(kb);
                int m=in.readInt(); if(m<1 || m>16384)throw new IOException(I18n.t(R.string.text_068));
                byte[] cb=new byte[m]; in.readFully(cb);
                return new Object[] {KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(kb)),
                    CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(cb))};
            }
        }
        Object[] generated=generate();
        PrivateKey generatedKey=(PrivateKey)generated[0];
        Certificate generatedCert=(Certificate)generated[1];
        byte[] encoded=generatedCert.getEncoded();
        FileOutputStream out=file.startWrite();
        try { DataOutputStream d=new DataOutputStream(out); byte[] k=generatedKey.getEncoded();d.writeInt(k.length);d.write(k);d.writeInt(encoded.length);d.write(encoded);d.flush();file.finishWrite(out); }
        catch(Exception e){file.failWrite(out);throw e;}
        return generated;
    }
    static Object[] generate() throws Exception {
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);
        KeyPair kp=generator.generateKeyPair();
        byte[] alg=der(0x30,join(new byte[]{6,9,0x2a,(byte)0x86,0x48,(byte)0x86,(byte)0xf7,0x0d,1,1,11},new byte[]{5,0}));
        byte[] name=der(0x30,der(0x31,der(0x30,join(new byte[]{6,3,0x55,4,3},der(12,"Scrcpy".getBytes(StandardCharsets.UTF_8))))));
        SimpleDateFormat f=new SimpleDateFormat("yyMMddHHmmss'Z'",Locale.US); f.setTimeZone(TimeZone.getTimeZone("UTC"));
        long now=System.currentTimeMillis();
        byte[] validity=der(0x30,join(der(23,f.format(new Date(now-86400000L)).getBytes(StandardCharsets.US_ASCII)),
            der(23,f.format(new Date(now+10L*365*86400000)).getBytes(StandardCharsets.US_ASCII))));
        byte[] tbs=der(0x30,join(der(0xa0,der(2,new byte[]{2})),der(2,new BigInteger(96,new SecureRandom()).add(BigInteger.ONE).toByteArray()),alg,name,validity,name,kp.getPublic().getEncoded()));
        Signature signer=Signature.getInstance("SHA256withRSA");signer.initSign(kp.getPrivate());signer.update(tbs);
        byte[] encoded=der(0x30,join(tbs,alg,der(3,join(new byte[]{0},signer.sign()))));
        Certificate c=CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(encoded));
        c.verify(kp.getPublic());
        return new Object[]{kp.getPrivate(),c};
    }
    private static byte[] join(byte[]... values) throws IOException { ByteArrayOutputStream b=new ByteArrayOutputStream();for(byte[] v:values)b.write(v);return b.toByteArray(); }
    private static byte[] der(int tag,byte[] data) throws IOException {
        ByteArrayOutputStream b=new ByteArrayOutputStream();b.write(tag);int n=data.length;
        if(n<128)b.write(n);else {int count=0,v=n;while(v>0){count++;v>>>=8;}b.write(128|count);for(int i=count-1;i>=0;i--)b.write(n>>>(8*i));}
        b.write(data);return b.toByteArray();
    }
}
