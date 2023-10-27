import CICDEnvUtils
import com.apigee.boot.ConfigType
import com.apigee.boot.Pipeline
import com.apigee.cicd.service.AssetService
import com.apigee.cicd.service.CIEnvInfoService
import com.apigee.cicd.service.DefaultConfigService
import com.apigee.cicd.service.FunctionalTestService
import com.apigee.cicd.service.OrgInfoService
import com.apigee.loader.BootStrapConfigLoad
import com.apigee.cicd.service.DeploymentInfoService
import groovy.json.JsonSlurper
import hudson.AbortException
import hudson.model.Cause
import pom
import JenkinsUserUtils

/*
This pipeline is used to perform CD on sharedflows
 */
def call(String build_number, String reposfName) {
    node {
        deleteDir()
        def shell = new shell()

        try {
            stage('access-token') {
			withCredentials([file(credentialsId: 'hdfcbank-apigee-runtime-uat', variable: 'serviceAccount')]) {
                            script {
                               git branch: 'master', credentialsId: 'newgithubid', url: 'https://github.hdfcbankuat.com/ALCMAPIGEEUAT/token-repo.git'
                            sh '''
                            ls -la
                            
                            sed -i -e 's/\r$//'  get-access-token.sh
                            sed -i -e 's/\r$//'  create-jwt-token.sh
                            access_token=$(sh get-access-token.sh ${serviceAccount} "https://www.googleapis.com/auth/cloud-platform"  "http://172.23.3.103:3128")
                            echo "${access_token}"  >> token1
                            cut -b 1-232 token1 > token
                            cat token
                            
                            
                            
                            '''
			    }
                           // echo "${access_token}"
                            //env.access = access_token
                            //echo "${env.access}"
                    }
                  }
                        def token = readFile"${env.WORKSPACE}/token"

            stage('init') {
                // TeamService service = new TeamService();

                if (!params.sfName) {
                    error "sfName Name is Required "
                }

                if (!params.version) {
                    error "Version is Required "
                }

            }

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
                                configLoad.setupAssetConfiguration("${env.API_SERVER_LOCATION}", "${sfName}")
                            } catch (MalformedURLException e) {
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

                            }
                        //}
                    }
            dir("${sfName}")
            {
                shell.pipe("git checkout tags/${params.version} -b ${params.version}")


                Maven maven = new Maven()
                JenkinsUserUtils jenkinsUserUtils = new JenkinsUserUtils()
                Npm npm = new Npm()
                def pom = new pom(),
                    proxyRootDirectory = "edge",
                    artifactId = pom.artifactId("./${proxyRootDirectory}/pom.xml"),
                    version = pom.version("./${proxyRootDirectory}/pom.xml"),
                    entityDeploymentInfos


                /*
                    Populating asset deployment data
                */
                AssetService.instance.data.branchMappings.each { branchMapping ->
                    if (branchMapping.branchPattern.contains("RC")) {
                        entityDeploymentInfos = branchMapping.deploymentInfo
                    }
                }


                dir(proxyRootDirectory) {

                    stage('build-sharedflow') {
                        maven.runCommand("mvn package -Phybrid-sharedflow")
                    }


                    stage('deploy-sharedflow') {
                        entityDeploymentInfos.each {
                            withCredentials([file(credentialsId: it.org, variable: 'serviceAccount')]) {
                                echo "deploying apirpoxy"
                                maven.runCommand("mvn -X apigee-enterprise:deploy -Phybrid-sharedflow -Dorg=${it.org} -Denv=${it.env} -Dbearer=${token}")
                            }
                            DeploymentInfoService.instance.setApiName(artifactId)
                            DeploymentInfoService.instance.setApiVersion(version)
                            DeploymentInfoService.instance.setEdgeEnv("${it.env}")
                            DeploymentInfoService.instance.saveDeploymentStatus("DEPLOYMENT-SUCCESS", env.BUILD_URL, jenkinsUserUtils.getUsernameForBuild())
                        }
                    }


                    if (DefaultConfigService.instance.steps.release) {

                        stage('upload-artifact') {
                            withCredentials([usernameColonPassword(credentialsId: 'artifactory_id', variable: 'NEXUS')]) {
                            maven.runCommand("mvn -X deploy")
                        }
                    }
                }
            }
        } }
        catch (any) {
            println any.toString()
            JenkinsUserUtils jenkinsUserUtils = new JenkinsUserUtils()
            currentBuild.result = 'FAILURE'
            DeploymentInfoService.instance.saveDeploymentStatus("FAILURE", env.BUILD_URL, jenkinsUserUtils.getUsernameForBuild())
        }
    }
}




