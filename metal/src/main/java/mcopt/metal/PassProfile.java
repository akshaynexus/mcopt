package mcopt.metal;
import java.util.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
/** Explicit diagnostic batches; idle path does not allocate or query native counters. */
public final class PassProfile {
 private static int remaining, completed, requested;
 private static boolean active;
 private static String group="", error="";
 private static Map<Integer,String> labels=new HashMap<>();
 private static final Map<String,double[]> sums=new LinkedHashMap<>();
 private static double gpuUs;
 private PassProfile(){}
 public static Map<String,Object> request(int frames){
  if(remaining>0||active||completed<requested)throw new IllegalStateException("Profile still pending");
  if(frames<1||frames>120)throw new IllegalArgumentException("1..120 frames");
  if(Long.getLong("mcopt.metal.trace",-1)>=0)throw new IllegalStateException("Use batch profiling separately from fixed-submit tracing");
  requested=remaining=frames;completed=0;gpuUs=0;error="";sums.clear();
  return Map.of("ok",true,"frames",frames);
 }
 public static boolean active(){return active;}
 public static void group(String name){group=name;}
 public static void label(long enc,String name){
  if(!active)return;
  int index=Native.profileCount(enc)/4-1;
  if(index>=0)labels.put(index,group.isEmpty()?name:group+" / "+name);
 }
 static void next(MetalEncoder encoder){
  if(remaining==0)return;
  active=Native.profileBegin(encoder.enc,512);
  if(!active){error="GPU stage timestamps unavailable";remaining=0;requested=completed;}
 }
 record Samples(long handle,int count,Map<Integer,String> labels){}
 static Samples end(MetalEncoder encoder){
  if(!active)return null;
  try(var stack=MemoryStack.stackPush()){
   long count=stack.ncalloc(4,1,4),handle=Native.profileEnd(encoder.enc,count);
   var sample=new Samples(handle,MemoryUtil.memGetInt(count),labels);
   labels=new HashMap<>();remaining--;active=false;return sample;
  }
 }
 static void read(MetalEncoder encoder,long cmd,Samples sample){
  try(var stack=MemoryStack.stackPush()){
   long data=stack.nmalloc(8,Math.max(1,sample.count)*8);
   Native.profileRead(encoder.ctx,sample.handle,sample.count,data);
   gpuUs+=Native.cmdGpuMicros(cmd);
   for(int i=0;i<sample.count/4;i++){
    String name=sample.labels.getOrDefault(i,"unlabeled #"+i);
    double[] total=sums.computeIfAbsent(name,k->new double[3]);
    double vs=MemoryUtil.memGetDouble(data+i*32L),ve=MemoryUtil.memGetDouble(data+i*32L+8);
    double fs=MemoryUtil.memGetDouble(data+i*32L+16),fe=MemoryUtil.memGetDouble(data+i*32L+24);
    if(vs>=0&&ve>=vs)total[0]+=ve-vs;
    if(fs>=0&&fe>=fs)total[1]+=fe-fs;
    total[2]++;
   }
   completed++;
  }
 }
 public static Map<String,Object> status(){
  var passes=new LinkedHashMap<String,Object>();
  sums.forEach((name,v)->passes.put(name,Map.of("vertex_ms",v[0]/Math.max(1,completed)/1000,"fragment_ms",v[1]/Math.max(1,completed)/1000,"encoders_per_frame",v[2]/Math.max(1,completed))));
  return Map.of("ok",error.isEmpty(),"complete",remaining==0&&!active&&completed==requested,"frames",completed,"gpu_ms",gpuUs/Math.max(1,completed)/1000,"passes",passes,"error",error,"note","stage durations may overlap; do not sum into frame time");
 }
}
