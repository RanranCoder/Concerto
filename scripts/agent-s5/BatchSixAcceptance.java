import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Disposable local MySQL acceptance. Generated credentials exist only in this process and child env. */
class BatchSixAcceptance {
    public static void main(String[] args)throws Exception{
        Path repo=Path.of("").toAbsolutePath().normalize();
        String suffix=UUID.randomUUID().toString().replace("-","").substring(0,12);
        Path output=repo.resolve("docs/audits/agent-acceptance-20261102/batch6-mysql-"+suffix);Files.createDirectories(output);
        String database="pams_s5_batch6_"+suffix,user="pams_s6_"+suffix;
        byte[] bytes=new byte[24];new java.security.SecureRandom().nextBytes(bytes);String password=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var config=new Properties();if(Files.exists(repo.resolve(".env")))try(var in=Files.newBufferedReader(repo.resolve(".env"))){config.load(in);}
        String rootPassword=System.getenv().getOrDefault("DB_PASSWORD",config.getProperty("DB_PASSWORD","root"));
        boolean created=false;var accounts=new ArrayList<String>();int exit=1;String cleanup="NOT_CREATED";long remainingSchemas=-1,remainingAccounts=-1;
        String url="jdbc:mysql://127.0.0.1:3306/"+database+"?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false";
        try(var admin=DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/?connectTimeout=3000&allowPublicKeyRetrieval=true&useSSL=false","root",rootPassword)){
            try(var sql=admin.createStatement()){
                if(!database.matches("pams_s5_batch6_[a-f0-9]{12}")||!user.matches("pams_s6_[a-f0-9]{12}"))throw new IllegalStateException("Invalid isolation identifiers");
                sql.execute("CREATE DATABASE `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");created=true;
                try{
                    for(String host:List.of("localhost","127.0.0.1")){
                        sql.execute("CREATE USER '"+user+"'@'"+host+"' IDENTIFIED BY '"+password+"'");accounts.add(host);
                        sql.execute("GRANT ALL PRIVILEGES ON `"+database.replace("_","\\_")+"`.* TO '"+user+"'@'"+host+"'");
                    }
                    System.out.println("Created disposable MySQL schema: "+database);
                    System.out.println("Evidence directory: "+output);
                    boolean browser=List.of(args).contains("--browser");
                    String suites=browser?"BatchSixBrowserIntegrationTest":"AgentEditorialIntegrationTest,ControlledExecutionIntegrationTest,BatchSixMySqlAcceptanceTest,BatchSixProcessRecoveryTest,AgentTaskPlanIntegrationTest,AgentNotificationIntegrationTest,ConversationRecallIntegrationTest";
                    var command=new ProcessBuilder("C:/dev/Git/bin/bash.exe","-c","mvn -q -f pams-backend/pom.xml -Dtest="+suites+" -Dpams.batch6.output=../docs/audits/agent-acceptance-20261102/batch6-mysql-"+suffix+" -Dsurefire.runOrder=alphabetical test");
                    command.directory(repo.toFile());var env=command.environment();
                    env.put("PAMS_S5_BATCH6","true");env.put("PAMS_S5_BATCH5","true");env.put("PAMS_S5_DB_URL",url);env.put("PAMS_S5_DB_USER",user);env.put("PAMS_S5_DB_PASSWORD",password);
                    env.put("PAMS_TEST_AGENT_PROVIDER","stub");env.put("PAMS_RUN_CONTROLLED_PLANNING_REAL","false");
                    command.redirectErrorStream(true).redirectOutput(output.resolve("batch6-mysql.log").toFile());
                    Process process=command.start();
                    try{if(!process.waitFor(20,TimeUnit.MINUTES))throw new IllegalStateException("Acceptance process timed out");exit=process.exitValue();}
                    finally{if(process.isAlive()){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();process.waitFor(30,TimeUnit.SECONDS);}}
                }finally{
                    // Only resources successfully created by this invocation can be removed.
                    if(created)sql.execute("DROP DATABASE `"+database+"`");
                    for(String host:accounts)sql.execute("DROP USER '"+user+"'@'"+host+"'");
                    try(var check=admin.prepareStatement("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?")){check.setString(1,database);try(var result=check.executeQuery()){result.next();remainingSchemas=result.getLong(1);}}
                    try(var check=admin.prepareStatement("SELECT COUNT(*) FROM mysql.user WHERE user=?")){check.setString(1,user);try(var result=check.executeQuery()){result.next();remainingAccounts=result.getLong(1);}}
                    if(remainingSchemas!=0||remainingAccounts!=0)throw new IllegalStateException("Disposable resource cleanup incomplete");cleanup="VERIFIED_REMOVED_CREATED_DATABASE_AND_ACCOUNTS";
                }
            }
        }catch(Exception failure){System.err.println("Isolation/acceptance failure type: "+failure.getClass().getSimpleName());exit=1;}
        Files.writeString(output.resolve("batch6-isolation.json"),"{\"database\":\""+database+"\",\"dedicatedAccount\":true,\"businessDatabaseUsed\":false,\"cleanup\":\""+cleanup+"\",\"remainingSchemas\":"+remainingSchemas+",\"remainingAccounts\":"+remainingAccounts+",\"exitCode\":"+exit+",\"completedAtUtc\":\""+Instant.now()+"\"}");
        System.out.println("Acceptance exit="+exit+" cleanup="+cleanup);if(exit!=0)System.exit(exit);
    }
}
