package org.example.timecoinweb.rag;
import org.example.pojo.Result;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import javax.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.function.Supplier;

@Component
public class AiTaskExecutor {
    private final ThreadPoolExecutor pool=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r->{Thread t=new Thread(r,"ai-worker");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    public DeferredResult<Result> submit(Supplier<?> action) {
        DeferredResult<Result> result=new DeferredResult<>(120000L);
        FutureTask<Void> task=new FutureTask<>(()-> {
            try { if(!result.isSetOrExpired()) result.setResult(Result.success(action.get())); }
            catch(ResponseStatusException e) { result.setErrorResult(e); }
            catch(Exception e) { result.setErrorResult(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"AI服务暂不可用")); }
            return null;
        });
        Runnable cancel=()->{task.cancel(true);pool.remove(task);};
        result.onTimeout(()->{result.setErrorResult(new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,"AI请求超时"));cancel.run();});
        result.onCompletion(cancel);
        try { pool.execute(task); } catch(RejectedExecutionException full) { result.setErrorResult(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"AI任务繁忙，请稍后重试")); }
        return result;
    }
    @PreDestroy public void close() { pool.shutdownNow(); }
}
