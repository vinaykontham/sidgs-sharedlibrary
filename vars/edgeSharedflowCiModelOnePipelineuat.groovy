import CICDEnvUtils
import com.apigee.boot.ConfigType
import com.apigee.boot.Pipeline
import com.apigee.cicd.service.AssetService
import com.apigee.cicd.service.CIEnvInfoService
import com.apigee.cicd.service.DefaultConfigService
import com.apigee.cicd.service.DeploymentInfoService
import com.apigee.cicd.service.OrgInfoService
import com.apigee.loader.BootStrapConfigLoad
import groovy.json.JsonSlurper
import groovy.transform.Field
import hudson.AbortException
import hudson.model.Cause
import pom

/*
This pipeline is used to perform CI on sharedflows
 */

def call(String branchType, String build_number) {

  node ("master") {
    deleteDir()
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
			                  def bearer = readFile"${env.WORKSPACE}/token"

      stage('Checkout') {
        checkout scm
      }
      echo " Stating CiPipeline for branchType = ${branchType}"

      Maven maven = new Maven()
      JenkinsUserUtils jenkinsUserUtils = new JenkinsUserUtils()
      Npm npm= new Npm()
      def pom = new pom(),
          proxyRootDirectory = "edge",
          artifactId = pom.artifactId("./${proxyRootDirectory}/pom.xml"),
          version =pom.version("./${proxyRootDirectory}/pom.xml"),
          entityDeploymentInfos

      echo artifactId


      withFolderProperties {
        BootStrapConfigLoad configLoad = new BootStrapConfigLoad();
        try {
          configLoad.setupConfig("${env.API_SERVER_LOCATION}")
          configLoad.setupAssetConfiguration("${env.API_SERVER_LOCATION}","${artifactId}")
        } catch (MalformedURLException e) {
          e.printStackTrace();
        }
      }

      /*
          Populating asset deployment data
       */
      AssetService.instance.data.branchMappings.each{ branchMapping ->
        if(branchMapping.branchPattern =~ branchType)
        {
          entityDeploymentInfos=branchMapping.deploymentInfo
        }
      }

      dir(proxyRootDirectory) {

        if (DefaultConfigService.instance.steps.unitTest) {
          stage('Apigee_Linting') {
			withCredentials([usernamePassword(credentialsId: 'artifactory_id', usernameVariable: 'usr', passwordVariable: 'pass')]) {
			nodejs('Node16') {
             
                sh '''
		curl -k -u $usr:$pass -O "https://artifactory.hdfcbankuat.com/artifactory/APIGEE/uat-extract.tar.gz"
		pwd
		ls -latr uat-extract.tar.gz 
				 tar -xvzf uat-extract.tar.gz
				 ls -latr 
				 npmbin=`npm bin`
				 mkdir target
				 eval $npmbin/apigeelint -s sharedflowbundle/ -f html.js >target/apigeelint
                '''
                echo "publish html report"
                            publishHTML(target: [
                            allowMissing         : false,
                            alwaysLinkToLastBuild: false,
                            keepAll              : true,
                            reportDir            : 'target',
                            reportFiles          : 'apigeelint.html',
                            reportName           : 'Linting HTML Report'
                       ])     
			}
	     }
      }        
	}
        if (DefaultConfigService.instance.steps.deploy) {

          stage('build-sharedflow') {
            maven.runCommand("mvn package -Phybrid-sharedflow")
          }

          stage('deploy-sharedflow') {
            entityDeploymentInfos.each {
              withCredentials([file(credentialsId: it.org, variable: 'serviceAccount')]) {
                echo "deploying sharedflow"
                maven.runCommand("mvn -X package apigee-enterprise:deploy -Phybrid-sharedflow -Dorg=${it.org} -Denv=${it.env} -Dbearer=${token}")
              }

              DeploymentInfoService.instance.setApiName(artifactId)
              DeploymentInfoService.instance.setApiVersion(version)
              DeploymentInfoService.instance.setEdgeEnv("${it.env}")
              DeploymentInfoService.instance.saveDeploymentStatus("DEPLOYMENT-SUCCESS", env.BUILD_URL, jenkinsUserUtils.getUsernameForBuild())
            }
          }

          if (DefaultConfigService.instance.steps.release) {
            stage('upload-artifact') 
                withCredentials([usernameColonPassword(credentialsId: 'artifactory_id', variable: 'NEXUS')]) {
              maven.runCommand("mvn -X deploy")
            }

          }
        }
      }

    } catch (any) {
      println any.toString()
      JenkinsUserUtils jenkinsUserUtils = new JenkinsUserUtils()
      currentBuild.result = 'FAILURE'
      DeploymentInfoService.instance.saveDeploymentStatus("FAILURE", env.BUILD_URL, jenkinsUserUtils.getUsernameForBuild())
    }
  }
}






