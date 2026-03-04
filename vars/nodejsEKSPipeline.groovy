    def call(Map configMap){
        pipeline {
            agent  {
                label 'AGENT-1'
            }
        environment {
            appversion = ''
            REGION = "us-east-1"
            ACC_ID = "485067906741"
        PROJECT = configMap.get('project')
        COMPONENT = configMap.get('component')
        }
        options {
            timeout(time: 30, unit: 'MINUTES')
            disableConcurrentBuilds()
        } 
        parameters {
            booleanParam(name: 'deploy', defaultValue: false, description: 'Toggle this value')
        } 
    // Build  
        stages {
            stage('Read package.json') {
                steps {
                    script {
                        def packageJson = readJSON file: 'package.json'
                        appversion = packageJson.version
                        echo "Package version: ${appversion}"
                    }
                }
            }
            stage('Install Dependencies') {
                steps {
                    script {
                    sh """
                            npm install
                    """
                    }
                }
            } 
            stage('Unit Testing') {
                steps {
                    script {
                    sh """
                            echo "unit tests"
                    """
                    }
                }
            } 
            // stage('Sonar Scan') {
            //     environment {
            //         scannerHome = tool 'sonar-7.2'
            //     }
            //     steps {
            //         script {
            //            // Sonar Server envrionment
            //            withSonarQubeEnv(installationName: 'sonar-7.2') {
            //                  sh "${scannerHome}/bin/sonar-scanner"
            //            }
            //         }
            //     }
            // } 
            //Enable webhook in sonarqube server and wait for results
            // stage("Quality Gate") {
            //     steps {
            //         timeout(time: 1, unit: 'HOURS') {
            //         waitForQualityGate abortPipeline: true }
            //     }
            // }    
            // stage('Check Dependabot Alerts') {
            //     environment { 
            //         GITHUB_TOKEN = credentials('github-token')
            //     }
            //     steps {
            //         script {
            //             // Fetch alerts from GitHub
            //             def response = sh(
            //                 script: """
            //                     curl -s -H "Accept: application/vnd.github+json" \
            //                         -H "Authorization: token ${GITHUB_TOKEN}" \
            //                         https://api.github.com/repos/DIVYA-GULLADURTHI/catalogue/dependabot/alerts
            //                 """,
            //                 returnStdout: true
            //             ).trim()

            //             // Parse JSON
            //             def json = readJSON text: response

            //             // Filter alerts by severity
            //             def criticalOrHigh = json.findAll { alert ->
            //                 def severity = alert?.security_advisory?.severity?.toLowerCase()
            //                 def state = alert?.state?.toLowerCase()
            //                 return (state == "open" && (severity == "critical" || severity == "high"))
            //             }

            //             if (criticalOrHigh.size() > 0) {
            //                 error "❌ Found ${criticalOrHigh.size()} HIGH/CRITICAL Dependabot alerts. Failing pipeline!"
            //             } else {
            //                 echo "✅ No HIGH/CRITICAL Dependabot alerts found." 
            //             }
            //         }
            //     }
            // }
            stage('Docker Build') {
                environment {
                    DOCKER_BUILDKIT = '0'
                }
                steps {
                    script {
                        withAWS(credentials: 'aws-credits', region: 'us-east-1') {
                            sh """
                            aws ecr get-login-password --region ${REGION} | \
                                docker login --username AWS --password-stdin \
                                ${ACC_ID}.dkr.ecr.${REGION}.amazonaws.com

                            docker build \
                                --platform linux/amd64 \
                                -t ${ACC_ID}.dkr.ecr.${REGION}.amazonaws.com/${PROJECT}/${COMPONENT}:${appversion} .

                            docker push \
                                ${ACC_ID}.dkr.ecr.${REGION}.amazonaws.com/${PROJECT}/${COMPONENT}:${appversion}
                            """
                        }
                    }
                }
            }
            stage('Check Scan Results') {
                steps {
                    script {
                        withAWS(credentials: 'aws-credits', region: 'us-east-1') {

                            // 1. Start scan (safe even if scan-on-push is enabled)
                            sh """
                            aws ecr start-image-scan \
                                --repository-name ${PROJECT}/${COMPONENT} \
                                --image-id imageTag=${appversion} \
                                --region ${REGION} || true
                            """

                            // 2. Wait for scan to complete (CLI-safe)
                            timeout(time: 5, unit: 'MINUTES') {
                                waitUntil {
                                    def status = sh(
                                        script: """
                                        aws ecr describe-image-scan-findings \
                                            --repository-name ${PROJECT}/${COMPONENT} \
                                            --image-id imageTag=${appversion} \
                                            --region ${REGION} \
                                            --query 'imageScanStatus.status' \
                                            --output text 2>/dev/null || echo NOT_READY
                                        """,
                                        returnStdout: true
                                    ).trim()

                                    echo "ECR scan status: ${status}"

                                    if (status == "FAILED") {
                                        error("ECR image scan failed")
                                    }

                                    return status == "COMPLETE"
                                }
                            }

                            // 3. Fetch findings (now guaranteed to exist)
                            def findings = sh(
                                script: """
                                aws ecr describe-image-scan-findings \
                                    --repository-name ${PROJECT}/${COMPONENT} \
                                    --image-id imageTag=${appversion} \
                                    --region ${REGION} \
                                    --output json
                                """,
                                returnStdout: true
                            ).trim()

                            def json = readJSON text: findings

                            // 4. Filter HIGH / CRITICAL
                            def highCritical = json.imageScanFindings.findings.findAll {
                                it.severity in ["HIGH", "CRITICAL"]
                            }

                            // 5. Fail only if needed
                            if (highCritical.size() > 0) {
                                echo "❌ Found ${highCritical.size()} HIGH/CRITICAL vulnerabilities!"
                                highCritical.each {
                                    echo "- ${it.name} (${it.severity})"
                                }
                                error("Build failed due to ECR vulnerabilities")
                            } else {
                                echo "✅ No HIGH/CRITICAL vulnerabilities found."
                            }
                        }
                    }
                }
            }
            stage('Trigger Deploy') {  
                when{
                expression { params.deploy }
                }
                steps {
                    script {
                    build job: 'catalogue-cd',
                        parameters: [
                            string(name: 'appversion', value: "${appversion}"),
                            string(name: 'deploy_to', value: 'dev')
                        ],
                    propagate: false, // even sg fails VPC will not be effected
                    wait: false // VPC will not wait for SG pipeline completion 
                    }
                }
            } 
        } 
        
        post { 
            always { 
                echo 'I will always say Hello again!'
                deleteDir()
            } 
            success { 
                echo 'Hello Success'
            } 
            failure { 
                echo 'Hello failure'
            }
        }
    }
}    


    