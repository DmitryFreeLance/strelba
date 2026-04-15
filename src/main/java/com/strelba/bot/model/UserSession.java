package com.strelba.bot.model;

import java.util.EnumMap;
import java.util.Map;

public class UserSession {
    private SurveyStep currentStep;
    private final Map<SurveyStep, String> answers = new EnumMap<>(SurveyStep.class);
    private AdminAction adminAction = AdminAction.NONE;
    private int dayPage = 0;
    private int usersPage = 0;

    public void resetSurvey() {
        answers.clear();
        currentStep = SurveyStep.A1_DATE_TIME;
    }

    public SurveyStep getCurrentStep() {
        return currentStep;
    }

    public void setCurrentStep(SurveyStep currentStep) {
        this.currentStep = currentStep;
    }

    public void putAnswer(SurveyStep step, String value) {
        answers.put(step, value);
    }

    public Map<SurveyStep, String> getAnswers() {
        return answers;
    }

    public AdminAction getAdminAction() {
        return adminAction;
    }

    public void setAdminAction(AdminAction adminAction) {
        this.adminAction = adminAction;
    }

    public int getDayPage() {
        return dayPage;
    }

    public void setDayPage(int dayPage) {
        this.dayPage = dayPage;
    }

    public int getUsersPage() {
        return usersPage;
    }

    public void setUsersPage(int usersPage) {
        this.usersPage = usersPage;
    }

    public enum AdminAction {
        NONE,
        WAITING_ADD_ADMIN_ID,
        WAITING_REMOVE_ADMIN_ID
    }
}
