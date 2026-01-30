package vue.llm.config;

import java.nio.file.Path;
import java.util.Map;

public class ProjectConfig {

    public final String projectRoot;
    public final String projectName;
    public final String routerRelativePath;

    public ProjectConfig(String projectRoot, String projectName, String routerRelativePath) {
        this.projectRoot = projectRoot;
        this.projectName = projectName;
        this.routerRelativePath = routerRelativePath;
    }

    public Path resolveRouterPath() {
        return Path.of(projectRoot, projectName, routerRelativePath);
    }

    public String resolveProjectPath() {
        return projectRoot + projectName;
    }

    public static final String root = "";
    public static final Map<String, ProjectConfig> PROJECTS = Map.of(
            "houserental",
            new ProjectConfig(
                    root + "house_rental_management/house-client",
                    "houserental",
                    root + "house_rental_management/house-client/src/router.js"
            ),
            "exam",
            new ProjectConfig(
                    root + "stu_exam_sys/tilas_front",
                    "exam",
                    root + "stu_exam_sys/tilas_front/src/router/index.js"
            ),
            "takeout",
            new ProjectConfig(
                    root + "UniTakeout/UniTakeout_ui/takeout-user",
                    "takeout",
                    root + "UniTakeout/UniTakeout_ui/takeout-user/src/router/index.js"
            ),
            "vehicle",
            new ProjectConfig(
                    root + "VehicleMangement-Web/frontend",
                    "vehicle",
                    root + "VehicleMangement-Web/frontend/src/router.js"
            ),
            "covid",
            new ProjectConfig(
                    root + "COVID/前端Vue/COVID-vue",
                    "covid",
                    root + "COVID/前端Vue/COVID-vue/src/router/index.js"
            ),
            "blogmanage",
            new ProjectConfig(
                    root + "Blog/blog-vue/admin",
                    "blogmanage",
                    root + "Blog/blog-vue/admin/src/router/index.js"
            ),
            "musicmanage",
            new ProjectConfig(
                    root + "music-website/music-manage",
                    "musicmanage",
                    root + "music-website/music-manage/src/router/index.ts"
            ),
            "dormitory",
            new ProjectConfig(
                    root + "dormitory_springboot/dormitoryms",
                    "dormitory",
                    root + "dormitory_springboot/dormitoryms/src/router/index.js"
            ),
            "questionnaire",
            new ProjectConfig(
                    root + "questionnaire/questionnaireFrontEnd",
                    "questionnaire",
                    root + "questionnaire/questionnaireFrontEnd/src/router/index.ts"
            ),

            "library",
            new ProjectConfig(
                    root + "Vue-Springboot-Library/vue",
                    "library",
                    root + "Vue-Springboot-Library/vue/src/router/index.js"
            )
    );
}
