package com.tools.module.app.service.impl;

import com.tools.common.dynamicquery.DynamicQuery;
import com.tools.common.model.PageBean;
import com.tools.common.model.Result;
import com.tools.common.util.DateUtils;
import com.tools.module.app.entity.AppTask;
import com.tools.module.app.repository.AppTaskRepository;
import com.tools.module.app.service.AppTaskService;
import org.apache.commons.lang3.StringUtils;
import org.quartz.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;


@Service("jobService")
public class AppTaskServiceImpl implements AppTaskService {

	@Autowired
	private DynamicQuery dynamicQuery;
    @Autowired
    private AppTaskRepository taskRepository;
    @Autowired
    private Scheduler scheduler;

	@Override
	public Result listQuartzEntity(AppTask task){
	    String countSql = "SELECT COUNT(*) FROM app_task";
	    String description = task.getDescription();
        if(!StringUtils.isEmpty(description)){
            countSql+=" WHERE task_name like '"+description+"%'";
        }
        Long totalCount = dynamicQuery.nativeQueryCount(countSql);
        PageBean<AppTask> data = new PageBean<>();
        if(totalCount>0){
            String nativeSql = "SELECT * FROM app_task";
            if(!StringUtils.isEmpty(description)){
                nativeSql+=" WHERE task_name like '"+description+"%'";
            }
            Pageable pageable = PageRequest.of(task.getPageNo(),task.getPageSize());
            List<AppTask> list = dynamicQuery.nativeQueryPagingList(AppTask.class,pageable, nativeSql);
            data = new PageBean<>(list, totalCount);
        }
        return Result.ok(data);
	}

    @Override
    @Transactional(rollbackFor=Exception.class)
    public void save(AppTask task) throws Exception{
        if(task.getOldGroup()!=null){
            JobKey key = new JobKey(task.getOldName(),task.getOldGroup());
            scheduler.deleteJob(key);
        }
        Class cls = Class.forName(task.getTaskClassName()) ;
        cls.newInstance();
        /**
         * ??????job??????
         */
        JobDetail job = JobBuilder.newJob(cls).withIdentity(task.getName(),
                task.getGroup())
                .withDescription(task.getDescription()).build();
        job.getJobDataMap().put("methodName", task.getMethodName());
        /**
         * ???????????????
         */
        CronScheduleBuilder cronScheduleBuilder = CronScheduleBuilder.cronSchedule(task.getCronExpression());
        Trigger trigger = TriggerBuilder.newTrigger().withIdentity("trigger"+task.getName(), task.getGroup())
                .startNow().withSchedule(cronScheduleBuilder).build();
        /**
         * ??????Scheduler????????????
         */
        scheduler.scheduleJob(job, trigger);
        if(task.getId()==null){
            task.setGmtCreate(DateUtils.getTimestamp());
        }
        task.setTriggerState("ACQUIRED");
        task.setGmtModified(DateUtils.getTimestamp());
        taskRepository.saveAndFlush(task);
    }

    @Override
    @Transactional(rollbackFor=Exception.class)
    public void delete(AppTask task) throws SchedulerException {
        TriggerKey triggerKey = TriggerKey.triggerKey(task.getName(), task.getGroup());
        /**
         * ???????????????
         */
        scheduler.pauseTrigger(triggerKey);
        /**
         * ???????????????
         */
        scheduler.unscheduleJob(triggerKey);
        /**
         * ????????????
         */
        scheduler.deleteJob(JobKey.jobKey(task.getName(), task.getGroup()));
        taskRepository.delete(task);
    }

    @Override
    @Transactional(rollbackFor=Exception.class)
    public void resume(AppTask task) throws SchedulerException {
        JobKey key = new JobKey(task.getName(),task.getGroup());
        if(StringUtils.equals(task.getTriggerState(),"PAUSED")){
            scheduler.resumeJob(key);
            task.setTriggerState("ACQUIRED");
        }else{
            scheduler.pauseJob(key);
            task.setTriggerState("PAUSED");
        }
        taskRepository.saveAndFlush(task);
    }

    @Override
    public long count() {
        return taskRepository.count();
    }
}
