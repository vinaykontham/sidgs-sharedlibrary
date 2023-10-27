import com.apigee.loader.BootStrapConfigLoad
import CICDEnvUtils
import hudson.AbortException
import hudson.model.Cause
import groovy.json.JsonSlurper
import pom
import com.apigee.boot.Pipeline
import com.apigee.cicd.service.CIEnvInfoService
import com.apigee.cicd.service.DeploymentInfoService
import com.apigee.cicd.service.DefaultConfigService
import com.apigee.cicd.service.OrgInfoService
import com.apigee.boot.ConfigType
import MavenSettings
import Maven
import JenkinsUserUtils
import shell
import BranchManagerService
/*
This pipeline is used for handling branch management on the repos
1. Feature branchches
2. Hotfix branches
3. Release branches
4. Release candidates
 */
def call(String operation,String repoProjectName) {

    node {
        deleteDir()
        def shell = new shell()
        try {

            withCredentials([
                    [$class          : 'UsernamePasswordMultiBinding',
                     credentialsId   : "newgithubid",
                     usernameVariable: 'scmUser',
                     passwordVariable: 'scmPassword'],
                    [$class          : 'UsernamePasswordMultiBinding',
                     credentialsId   : "git_test1_oauth",
                     usernameVariable: 'scmClient',
                     passwordVariable: 'scmSecret'],
            ])

                    {

                        withFolderProperties {

                            BootStrapConfigLoad configLoad = new BootStrapConfigLoad();
                            try {
                                scmAPILocation = env.API_SCM_LOCATION
                                scmOauthServerLocation = env.API_SCM_OAUTH_SERVER
                                configLoad.setupConfig("${env.API_SERVER_LOCATION}")
                            }
                            catch (MalformedURLException e) {
                                e.printStackTrace();
                            }
                        }

                       /*stage("get scm token") {
                            def userDetails = "${env.scmClient}:${env.scmSecret}";
                            def encodedUser = userDetails.bytes.encodeBase64().toString()
                            def response = httpRequest httpMode: 'POST',
                                    customHeaders: [[name: "Authorization", value: "Basic ${encodedUser}"], [name: "content-type", value: "application/x-www-form-urlencoded"]],
                                    url: "${scmOauthServerLocation}",
                                    requestBody: "grant_type=client_credentials"
                            def responseJson = new JsonSlurper().parseText(response.content)
                            scmAccessToken = responseJson.access_token
                            
                        }*/


                        stage('Checkout') {
                            //wrap([$class: 'MaskPasswordsBuildWrapper', varPasswordPairs: [[password: scmAccessToken, var: 'SECRET']]]) {
                              shell.pipe("git clone https://${scmUser}:${scmPassword}@github.hdfcbankuat.com/ALCMAPIGEEUAT/${sfName}.git")
                                shell.pipe("ls -la")
                                shell.pipe("pwd")
                              shell.pipe("cd ${sfName} ")
                                shell.pipe("ls -la")
                            }
                        //}
                    }



            dir("${sfName}") {
                BranchManagerService branchManagerService = new BranchManagerService()
                switch (operation) {
                    case "release-create":
                        branchManagerService.createRelease(params.sfName)
                        break
                    case "release-close":
                        branchManagerService.finishRelease(params.sfName)
                        break
                    case "release-candidate-create":
                        branchManagerService.createReleaseCandidate(params.sfName)
                        break
                    case "feature-create":
                        branchManagerService.createFeature(params.sfName)
                        break
                    case "feature-close":
                        branchManagerService.finishFeature(params.sfName)
                        break
                    case "hotfix-create":
                        branchManagerService.createHotFix(params.sfName)
                        break
                    case "hotfix-close":
                        branchManagerService.finishHotFix(params.sfName)
                        break
                    default:
                        echo "invalid operation"
                        break
                }
            }
        }
            catch (any) {
                JenkinsUserUtils jenkinsUserUtils = new JenkinsUserUtils()
                println any.toString()
                currentBuild.result = 'FAILURE'
                DeploymentInfoService.instance.saveDeploymentStatus("FAILURE", env.BUILD_URL,jenkinsUserUtils.getUsernameForBuild())
            }
        }
    }

@NonCPS
def parseJsonText(String json) {
    def object = new JsonSlurper().parseText(json)
    if(object instanceof groovy.json.internal.LazyMap) {
        return new HashMap<>(object)
    }
    return object
}
def getRepoCreated() {
    def userDetails = "-v -u" +
            "username=${env.scmUser}:password=${env.scmPassword} ";
    return userDetails as String;
}

def getUsernameForBuild() {
    def causes = currentBuild.rawBuild.getCauses()
    for (Cause cause in causes) {
        def user;
        if (cause instanceof hudson.model.Cause.UserIdCause) {
            hudson.model.Cause.UserIdCause userIdCause = cause;
            user = userIdCause.getUserName()
            return user
        }
    }
    return null
}

def isBuildCauseUserAction() {
    def causes = currentBuild.rawBuild.getCauses()
    for (Cause cause in causes) {
        if (cause instanceof hudson.model.Cause.UserIdCause) return true
    }
    return false
}

def getPom() { return new pom(); }

def runCommand(String command) {
    if (!isUnix()) {
        println command
        if (command.trim().toLowerCase().startsWith("mvn")) {
            withMaven(globalMavenSettingsConfig: 'jfrog2', maven: 'maven') {
                bat returnStdout: true, script: "${command}"
            }
        } else {

            bat returnStdout: true, script: "${command}"
        }
    } else {
        println command
        if (command.trim().toLowerCase().startsWith("mvn")) {
            withMaven(globalMavenSettingsConfig: 'jfrog2', maven: 'maven') {
                sh returnStdout: true, script: command
            }
        } else {
            sh returnStdout: true, script: command
        }

    }
}
