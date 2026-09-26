package com.zlzcode.codeagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import com.zlzcode.codeagent.evaluation.cli.EvaluationCommand;

import java.util.Arrays;

@SpringBootApplication
public class CodeAgentApplication {

    public static void main(String[] args) {
        /*
         * 背景：案例准备与独立判卷不需要日常 Web 服务或用户运行状态。
         * 设计意图：显式命令在 Spring 启动前分流，复用可执行包而非把正式入口藏在 JUnit 中。
         * 关键约束：不能先启动应用再执行命令；否则会占用端口并初始化日常会话、审批等持久化状态。
         */
        if (args.length > 0 && args[0].equals("evaluation")) {
            System.exit(new EvaluationCommand().execute(Arrays.copyOfRange(args, 1, args.length)));
            return;
        }
        SpringApplication application = new SpringApplication(CodeAgentApplication.class);
        /*
         * 背景：Spring Boot 默认启用 AWT 无头模式，Windows 原生目录选择器会因此被判定为不可用。
         * 设计意图：在应用启动阶段保留桌面选择能力，而不是绕过原生选择器另建一套路径输入流程。
         * 关键约束：必须在 application.run 之前关闭无头模式，否则 AWT 环境初始化后再修改不会生效。
         */
        application.setHeadless(false);
        application.run(args);
    }
}
